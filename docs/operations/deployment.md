# Production deployment

`compose.yaml` is a reproducible release-candidate topology, not a development shortcut.
It runs PostgreSQL, OPA, Prudence and the UI. Ernest and the OIDC provider remain external,
because they are shared control-plane dependencies and must be operated independently.

1. Copy `.env.example` to `.env` and replace every placeholder.
2. The schema is created and evolved by Flyway on startup (`src/main/resources/db/migration`,
   starting at `V1__baseline.sql`); Hibernate only validates it and never mutates it. Schema
   changes ship as new versioned migrations. A database that already holds tables from an
   earlier `ddl-auto=update` run must be baselined first (`spring.flyway.baseline-on-migrate`).
3. Build and start: `docker compose up --build`.
4. Confirm `http://localhost:8099/actuator/health` reports `UP` before exposing the UI.

The OPA bundle identity (`PRUDENCE_OPA_POLICY_VERSION` and `PRUDENCE_OPA_POLICY_HASH`) is
mandatory release metadata. It is stored with every governance decision, making a decision
reproducible against the exact policy bundle that made it.

## Evidence delivery to Ernest

All governance evidence — PDP decisions, review events (approve/reject) and attestations —
is committed to the database before any call to Ernest, and then delivered by one worker.
Decisions go through the `evidence_outbox` table; review events and attestations carry the
same delivery columns on their own rows. Reviews and attestations are delivered right after
commit (an attestation is usually returned already recorded); decisions are delivered in the
background so the PDP never waits on Ernest. Every retry reuses the record's id (outbox
event, review or attestation id) as Ernest's `inferenceId`. Ernest's responses are handled
by an explicit contract:

| Ernest response | Handling | Signal |
|---|---|---|
| 2xx | delivered | — |
| network error, timeout, 408/409/429, 5xx | exponential backoff (max 1h), dead-lettered after `PRUDENCE_EVIDENCE_MAX_ATTEMPTS` (20) | WARN per retry, ERROR when dead-lettered |
| 401/403, Ernest not configured | retried every 5 min, never dead-lettered | one ERROR when it starts, one INFO on recovery |
| other 4xx (e.g. invalid signature) | dead-lettered immediately | ERROR |

Posture reflects the decision and review backlog: a system with dead-lettered evidence is
**RED** (that proof is missing until someone re-drives it — see below); evidence still undelivered after
`PRUDENCE_EVIDENCE_OVERDUE_AFTER_MINUTES` (15) makes it **AMBER**. An attestation's own
delivery state shows in its verification (`PENDING` while queued, `UNSEALED` if dead-lettered).
"Recorded" and "qualified-sealed" are distinct: without a QTSP, evidence is recorded in Ernest
unsealed, and verification still confirms it. Delivery is at-least-once;
Ernest does not yet deduplicate by `inferenceId`, so a crash between Ernest's acceptance and
the outbox update can produce a duplicate block carrying the same id.

## Periodic review (approval lifecycle)

An approval is valid for `PRUDENCE_APPROVAL_VALIDITY_DAYS` (365). The PDP stops allowing a
system the moment its approval lapses. An hourly sweep (`PRUDENCE_LIFECYCLE_SWEEP_MS`) then:

- issues a one-off **renewal notice** (`REVIEW_DUE` in the audit trail; posture AMBER) when an
  approval enters its final `PRUDENCE_REVIEW_WINDOW_DAYS` (30);
- marks a lapsed approval **EXPIRED** (`EXPIRE` in the audit trail; posture RED).

Reviewers renew a still-valid approval from *Approvals → Renewal due*
(`POST /api/registry/systems/{id}/renew`); the system stays `APPROVED` and keeps running,
and a high-risk renewal needs two independent sign-offs. An expired system cannot be renewed:
it must be resubmitted and go through the full review again. Every state change locks the
system's row, so concurrent reviewers or several Prudence instances are serialised.

## Alerts (email)

Prudence emails the people who must act. Every alert is stored in the same transaction as the
event that causes it and then sent by the delivery worker (same retries, dead-letter and
configuration handling as evidence), so an SMTP outage delays alerts but never loses them.
Each alert has a deduplication key, so it is sent once regardless of sweeps, retries,
restarts or the number of Prudence instances.

| Alert | When | To |
|---|---|---|
| Renewal review due | an approval enters its review window | governance + system owner |
| Approval expired | an approval lapsed without renewal | governance + system owner |
| Ungoverned executions | drift check (every `PRUDENCE_DRIFT_CHECK_MS`, 15 min) finds executions for a non-approved or unregistered system — once per system and approval state | governance |
| Evidence could not be delivered | a decision/review/attestation record is dead-lettered | operations |
| Delivery refusing configuration | Ernest rejects Prudence's credentials/endpoint — once per target per day | operations |

Configure SMTP with `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
`SPRING_MAIL_PASSWORD` (STARTTLS via `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE`), and
recipients with `PRUDENCE_ALERTS_GOVERNANCE_TO` / `PRUDENCE_ALERTS_OPERATIONS_TO`
(comma-separated), `PRUDENCE_ALERTS_FROM` and optionally `PRUDENCE_CONSOLE_URL` (linked in
every email). Outside `dev`/`test` Prudence refuses to start without SMTP and both recipient
lists. In `dev` without SMTP, alerts are written to the log instead of emailed.
`GET /api/alerts` lists recent alerts with their delivery state. Failures of the email
channel itself are logged, not alerted (it cannot report on itself).

## Incident register (AI Act Art. 73)

*Incidents* records AI incidents and computes what the AI Act requires in response. Prudence
does **not** file reports with authorities; it tracks the obligation, alerts people, and
records each filing a human made (recipient, reference, submission time).

- **Classification** follows the serious-incident definition (Art. 3(49)): death or serious
  harm to health, serious and irreversible critical-infrastructure disruption,
  fundamental-rights infringement, serious harm to property or the environment — or not
  serious. A causal link that is ruled out lifts the obligation.
- **Deadline**, counted from awareness: 2 days for a widespread infringement or
  critical-infrastructure disruption (Art. 73(3)), 10 days for a death (Art. 73(4)), 15 days
  otherwise (Art. 73(2)). Re-triage can move it earlier, never reset it.
- **Recipient** depends on the system's registered role: provider → market surveillance
  authority of the Member State(s) concerned (Art. 73(1)); deployer → the provider first, then
  the importer/distributor and the authority (Art. 26(5)).
- **Lifecycle**: OPEN → initial (possibly incomplete) report → final report with root cause and
  corrective actions (Art. 73(5)) → CLOSED. A reportable incident cannot close before its final
  report; a non-serious one closes with a note.
- Opening an incident can **suspend** the AI system (PDP denies it at once; reinstatement is a
  fresh submission and review).
- **Alerts**: a serious incident immediately; "report due" 24h before the deadline; "overdue"
  once it passes (checked every `PRUDENCE_INCIDENT_DEADLINE_CHECK_MS`, 15 min). Posture shows an
  open serious incident as AMBER and an overdue report as RED.
- Every step is appended to the incident's **timeline** and recorded in Ernest (model
  `prudence-incidents`), so the dates of awareness and filing are tamper-evident.

The deadline rules encode the regulation as of Regulation (EU) 2024/1689; have legal review the
classification guidance before relying on it. Sector regimes (e.g. DORA ICT-incident
reporting for financial entities) are not covered.

## High-volume evidence: Kafka or Ernest's event-ingestor (optional)

Every governed agent step costs one PDP decision, and Prudence records each decision in Ernest.
By default decisions go direct, batched (`POST /api/inferences/batch`). For agent platforms with
many tool calls, use **Kafka** (Ernest ADR-005) — the recommended high-volume transport:
Prudence publishes each decision to a topic (idempotent producer, `acks=all`, keyed by AI
system) and Ernest's `event-ingestor kafka` consumer group records it. "Delivered" means the
broker acknowledged the record; Ernest deduplicates replays by the decision id.

```
PRUDENCE_ERNEST_DECISION_TRANSPORT=kafka
PRUDENCE_ERNEST_KAFKA_BOOTSTRAP_SERVERS=kafka-1.example.eu:9092,kafka-2.example.eu:9092
PRUDENCE_ERNEST_KAFKA_TOPIC=prudence.decisions
```

Grant Prudence write and Ernest's consumer group read on the topic only: the topic ACL is what
Ernest records as the events' verification (`kafka_acl`). Size the topic's partitions for the
number of consumers you want (8 by default in the local script). Measured on one host: ≥ 7,000
decisions/s (see `loadtest-results.md`).

Without Kafka, PDP decisions can instead be **queued on Ernest's event-ingestor over HTTP**
(Redis Streams), which absorbs bursts and deduplicates by the decision id (one request per
decision, ~2,500/s on the same host):

```
PRUDENCE_ERNEST_DECISION_TRANSPORT=ingestor
PRUDENCE_ERNEST_INGESTOR_URL=https://ernest-ingestor.example.eu
PRUDENCE_ERNEST_INGESTOR_KEY=<EVENT_INGESTOR_API_KEY of the ingestor>
```

The agent SDK (`prudence_governance.ErnestEvidence`) has the same option for execution records:
`ERNEST_INGESTOR_URL` / `ERNEST_INGESTOR_KEY`.

Trade-offs, all deliberate:
- **No qualified seal.** The ingestor stores its own event envelope in the block, so a QTSP seal
  over the direct block-data would not verify. Prudence refuses to start with
  `decision-transport=ingestor` or `kafka` and a QTSP configured; the SDK raises on a sealer +
  ingestor.
- **"Delivered" means durably queued (202, or the broker's ack for Kafka), not appended.** If the ingestor's worker later fails
  to append, the failure is recorded in Ernest (`event_failures`), not in Prudence's outbox.
- **Only decisions move.** Reviews, attestations and incidents are low-volume, need block
  receipts and qualified seals, and always go direct.

Check on the Ernest side before relying on it: the ingestor trims its stream at
`REDIS_STREAM_MAXLEN` (10 000, approximate), which can drop **unprocessed** events if the worker
falls that far behind; and it rate-limits per client IP (`RATE_LIMIT_RPS` 20, burst 40), so many
agents behind one NAT share that budget (Prudence retries 429s; the SDK does not yet).

## Re-driving dead-lettered records

`GET /api/evidence/dead-letters` lists every record the delivery worker gave up on — decisions,
reviews, attestations, incident events and alert emails — with its last error. Once the cause is
fixed (Ernest reachable, credentials or payload corrected),
`POST /api/evidence/dead-letters/{target}/{id}/retry` requeues it with a fresh attempt budget and
delivers it immediately (`read-write` role; the operator is logged). The same list, with a Retry
button, is at the bottom of the *Posture* tab. Retrying a record that is not dead-lettered
returns 409. There is deliberately no "discard": giving up on a piece of evidence should be a
recorded decision, which is not built yet.

## Evidence delivery throughput

The delivery worker drains the outbox continuously (`PRUDENCE_EVIDENCE_OUTBOX_DELAY_MS`, default
2 s, only matters when idle), with `prudence.evidence.delivery-concurrency` (8) concurrent
senders. PDP decisions go to Ernest in batches of `prudence.ernest.batch-size` (200, max 500)
through `POST /api/inferences/batch`; an Ernest without that endpoint is detected (404) and
decisions fall back to one-by-one. Ernest counts quotas per credential
(`THROTTLE_AUTHENTICATED_LIMIT`, Ernest side). Measured on one host: ~1,400 decisions/s direct,
~2,100/s through the ingestor (see `docs/operations/loadtest-results.md`).

### With Ernest's Merkle-batched evidence (Ernest ADR-004)

When Ernest runs with `EVIDENCE_BATCHING=on`, it accepts a record before chaining it: `202`
(per item in the batch endpoint) counts as delivered, and the stored `blockIndex` stays empty
("chaining pending"). Prudence fetches the inclusion proof lazily when it needs it —
attestation verification calls `GET /api/events/api%2F<model>/<id>/proof` and verifies it
itself (block hash and RFC 6962 audit path, `ErnestEventProof`), reporting `PENDING` until the
record's batch is chained. Against an Ernest with batching off (proof endpoint answers 404) it
falls back to scanning the model's provenance. Re-sends are idempotent in Ernest; a re-send with
different content is a permanent delivery failure.
