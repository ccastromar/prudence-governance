# Chestertown operator guide

How to deploy and run the Chestertown suite in production: **AOS** (orchestrate), **Prudence**
(govern) and **Ernest** (prove). For the person who installs it, configures the identity
provider and keeps it running. Each app's own documentation goes deeper; this guide is the path
through all three.

Versions that work together: [`compatibility.md`](../compatibility.md). What the suite does for
the EU AI Act and ISO/IEC 42001: [`../compliance/traceability.md`](../compliance/traceability.md).
Prudence's own operations (evidence delivery, approvals, alerts, incidents):
[`deployment.md`](deployment.md).

## 1. What you deploy

```text
                         browsers (OIDC sign-in)
                                   │
        ┌──────────────────────────┼──────────────────────────┐
   AOS console              Prudence console            Ernest console
        │                          │                          │
       AOS ── decide/report ──▶ Prudence ── reads ─────────▶ Ernest backend ──▶ MongoDB
        │                          │  │                        ▲
        │                          │  └── PostgreSQL, OPA      │ chains evidence
        │                          │                           │
        └── audit journal ──┐      └── decisions ──┐     Ernest event-ingestor
                            ▼                      ▼           ▲
                          Kafka (TLS): aos.executions, prudence.decisions
```

| Component | What it holds | Exposed to |
| --- | --- | --- |
| **AOS** (Go) | Pipelines; SQLite state; the local **audit journal** (hash-chained, the outbox to Ernest) | Its console; never directly to the internet |
| **Prudence** (Java 25) | Registry, policies, approvals, oversight, incidents; **PostgreSQL**; the PDP backed by **OPA** | Its console; runtimes call its PDP |
| **Ernest** (Node.js) | Tamper-evident evidence in **MongoDB**; optional blockchain anchoring | Its console; auditors read it |
| **Ernest event-ingestor** (Go) | Consumes Kafka (and `POST /events`) into Ernest's store | Internal only |
| **Kafka** | Decisions and executions in transit | Internal only |
| **OIDC provider** | People and machine identities (Keycloak, Entra ID, Dex tested) | Browsers and the apps |
| **SMTP relay** | Alerts (expiries, drift, lost evidence, incident deadlines) | Prudence |

Datastores and Kafka belong on an internal network that the public edge cannot reach. MongoDB and
Kafka are not authenticated per client in the reference deployment; the network is what keeps
them private.

## 2. Before you start

- **Host**: Docker with Compose, or Kubernetes. Platform versions: `compatibility.md`.
- **DNS and TLS** for the three consoles and the identity provider. The apps run plain HTTP behind
  a TLS-terminating proxy or tunnel.
- **An OIDC provider** you administer (section 3).
- **An SMTP relay** for alerts.
- **A secret store** (a vault, Compose secrets or Kubernetes secrets) for the keys in section 4.
- **Your governance model**: start from a template in `contract/governance/templates/` (a small or
  medium organization, a bank, a public body) and check it:

  ```bash
  python contract/governance/ct-model/ct_model.py check --strict governance-model.yaml
  ```

## 3. Identity

Every app authorizes by **governance functions** (`contract/governance/README.md`). The
governance model maps your provider's roles or groups to them, and all three apps read the same
model: `prudence.governance.model` (Prudence), `AOS_GOVERNANCE_MODEL`, `ERNEST_GOVERNANCE_MODEL`.

`ct_model.py` generates the provider side from the model:

```bash
python contract/governance/ct-model/ct_model.py keycloak governance-model.yaml -o realm.json   # Keycloak
python contract/governance/ct-model/ct_model.py entra governance-model.yaml -o app-roles.json  # Entra ID
python contract/governance/ct-model/ct_model.py explain governance-model.yaml '{"roles":["…"]}' # what a token gets
```

You need these clients in the provider:

| Client | Kind | For |
| --- | --- | --- |
| Prudence console | Public, PKCE | People signing in to Prudence (audience: Prudence's API audience) |
| AOS console | Public, PKCE | People signing in to AOS |
| Ernest console | Public, PKCE | People signing in to Ernest |
| AOS → Prudence | Confidential, client credentials | The gate's decisions (`pdp.decide`) and execution reports (`execution.report`), with a `preferred_username` mapper |
| Other runtimes | Confidential, client credentials | One per orchestrator that calls the PDP (`integrations/`) |

Each app checks issuer, audience and signature:

| App | Settings |
| --- | --- |
| Prudence | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`, `…_JWT_AUDIENCES`, `PRUDENCE_IDENTITY_ROLES_CLAIM`, `PRUDENCE_IDENTITY_ROLE_MAPPING` |
| AOS | `AOS_JWT_ISSUER`, `AOS_JWT_AUDIENCE`, `AOS_JWK_URL`, `AOS_ROLE_CLAIM`, `AOS_ROLE_MAP` |
| Ernest | `OIDC_ISSUER`, `OIDC_AUDIENCE`, `OIDC_ROLE_CLAIM`, `OIDC_ROLE_MAP` |

Check a provider before go-live with five of its tokens: `scripts/idp-acceptance.sh`
(`docs/design/idp-interoperability.md`).

## 4. Secrets and keys

Keep every value below in your secret store, never in an image or a repository.

| Secret | App | Purpose | If it changes or is lost |
| --- | --- | --- | --- |
| **AOS evidence key** (`AOS_EVIDENCE_KEY` or `_FILE`, ≥ 32 bytes) | AOS | Audit hashes leave AOS only as HMAC digests | **Never change it.** AOS's journal no longer matches Ernest, and replays are not deduplicated. Back it up |
| **Prudence pseudonym key** (`PRUDENCE_EVIDENCE_PSEUDONYM_KEY` or `_FILE`, ≥ 32 bytes) | Prudence | People leave Prudence's evidence only as HMAC pseudonyms | **Never change it.** The same person gets another pseudonym. Back it up |
| Ernest write key (`ERNEST_API_KEY`) | Ernest, Prudence (`PRUDENCE_ERNEST_API_KEY`) | Evidence writes | Rotate both sides together |
| Ernest read key (`ERNEST_READ_API_KEY`) | Ernest, its readers | Read-only access | Rotate freely |
| Ingestor key | Ernest event-ingestor, its HTTP emitters | `POST /events` (with an optional provider HMAC secret) | Rotate both sides together |
| AOS PDP client secret (`PRUDENCE_PDP_CLIENT_SECRET`) | AOS | Client credentials for the gate | Rotate in the provider and AOS |
| Database passwords | PostgreSQL (Prudence), the provider's database | | Rotate as usual |
| Kafka TLS material | Kafka and its clients | Encrypted transport | Reissue; clients trust the CA |
| SMTP credentials | Prudence | Alerts | Rotate as usual |

Never set `PUBLIC_ERNEST_API_KEY` in a production build of Ernest's console: it would be shipped
to every browser. People sign in with OIDC.

## 5. Configure each app

Prudence and AOS **refuse to start in production** without what they need, so a
misconfiguration fails at start, not at the first request. Ernest does not yet: check it yourself.

| App | Outside development |
| --- | --- |
| Prudence | Refuses to start without: issuer and audience; database URL, user and password; Ernest URL and write key (or the CloudEvents sink); Kafka over SSL/SASL_SSL when used; the pseudonym key; SMTP host and alert recipients; CORS origins |
| AOS | Refuses to start without: the evidence key when evidence goes to Ernest; Kafka over SSL/SASL_SSL; the ingestor key for HTTP evidence |
| Ernest | **Starts open when no credential is configured**: without `ERNEST_API_KEY`, `ERNEST_READ_API_KEY` and OIDC, anyone can read and write evidence. Always set the write key, the read key and OIDC (`docs/deployment-vps.md` in Ernest), and check that an anonymous write answers `401` |

Choices to make:

- **Evidence transport**: decisions to Ernest directly (`PRUDENCE_ERNEST_DECISION_TRANSPORT=direct`),
  or through Kafka (`kafka`, recommended at volume) or the ingestor's HTTP endpoint (`ingestor`).
  AOS ships its journal through Kafka (`AOS_EVIDENCE_KAFKA_BROKERS`) or HTTP (`ERNEST_INGESTOR_URL`).
- **Ernest's batching** (`EVIDENCE_BATCHING`): `events` (the default: one block per event,
  deduplicated) or `on` (one block per batch with an RFC 6962 root, for volume). Set the same value
  on the backend and the event-ingestor. Never `off` where Prudence or AOS write: Ernest would not
  deduplicate retries.
- **Policy bundle identity**: `PRUDENCE_OPA_POLICY_VERSION` and `PRUDENCE_OPA_POLICY_HASH` are
  recorded with every decision; set them from your release.

## 6. First start

1. Start the datastores, Kafka and the identity provider.
2. Start Ernest (backend, then event-ingestor), then Prudence, then AOS, then the consoles.
3. Check health:

   | App | Endpoint |
   | --- | --- |
   | Prudence | `GET /actuator/health` |
   | AOS | `GET /health/live`, `GET /health/ready` |
   | Ernest | `GET /health` (backend), `GET /health` (event-ingestor) |

4. Prove the loop with a test system:
   1. register it in Prudence;
   2. check that AOS is **denied** a run of it while it is not approved;
   3. approve it;
   4. run it from AOS;
   5. see the decision and the execution in Ernest;
   6. see the system **GREEN** in Prudence's posture;
   7. check that `GET /api/verify` in Ernest answers `isValid: true`.

## 7. Day-to-day

### What to watch

| Signal | Where | Means |
| --- | --- | --- |
| Posture **RED** | Prudence | A system must not run (blocked, rejected, suspended, expired) or its evidence was dead-lettered |
| Posture **AMBER** | Prudence | Pending review, renewal due, evidence late, or the evidence layer unavailable |
| Drift alert | Email (governance) | Runs of a system that is not approved |
| Execution sources mismatch | Prudence | A runtime reported a run Ernest has no evidence of, or the reverse |
| Incident deadline alert | Email | An Art. 73 report is due in 24 h, or overdue |
| Approval expiry notice | Email | Renew within the review window or the system stops |
| `isValid: false` | Ernest `GET /api/verify` | The chain does not verify: treat as an incident (section 8) |

Metrics: AOS exposes `/metrics` and the event-ingestor its Prometheus port (`METRICS_PORT`); keep
both off the public edge. Prudence exports OpenTelemetry when `PRUDENCE_OTEL_ENABLED=true`.

### Backups

Back up together, and copy off the host:

- PostgreSQL (Prudence) and the provider's database;
- MongoDB (Ernest);
- AOS's state and audit journal;
- Kafka TLS material;
- **the AOS evidence key and the Prudence pseudonym key**.

Test a restore regularly. Without the two keys, a restored suite still runs but can no longer link
old evidence.

### Scaling

- **Prudence**: runs as several instances on one PostgreSQL; state changes lock the system's row.
- **Ernest**: splits into readers, writers and one chainer (`ERNEST_ROLE`; Ernest's ADR-004).
- **AOS**: one instance per audit journal.

## 8. Runbooks

| Situation | What happens | What to do |
| --- | --- | --- |
| **Prudence or OPA down** | Fail closed: every governed run is denied (AOS stops before any step) | Restore it; nothing to replay, the runs did not happen |
| **Ernest down** | Prudence keeps deciding; evidence waits in its outbox and AOS's journal, then ships; posture AMBER | Restore it; check the backlog drains and nothing is dead-lettered |
| **Kafka down** | AOS still runs; its journal and Prudence's outbox keep the evidence | Restore it; the shippers resume from their cursors |
| **Evidence dead-lettered** (posture RED) | A record was refused or ran out of retries | Find the cause (`GET /api/evidence/dead-letters`), fix it, then re-drive (`POST /api/evidence/dead-letters/{target}/{id}/retry`) |
| **Stop a system now** | | Record an incident with *suspend the system*: the PDP denies it at once. A RED monitoring signal or a substantial change also suspends. It returns only through review |
| **Serious incident** | Deadlines run from awareness | Record it in Prudence with its category; the deadline and alerts follow |
| **Identity provider down** | Nobody signs in; tokens already issued work until they expire; machine clients cannot get new tokens, so governed runs stop | Restore it |
| **Ernest's chain does not verify** | Evidence may have been altered | Stop writes, keep the database as it is, compare with the latest anchor and an older backup, investigate as a security incident |
| **An evidence key is lost** | The apps run with a new key, but old digests and pseudonyms cannot be linked | Restore the key from backup before generating a new one; record the change of key if it is truly lost |
| **Host lost** | | Restore every backup listed above, the keys first; then verify Ernest's chain and Prudence's posture |

## 9. Security checklist

- [ ] Datastores, Kafka, the event-ingestor and the metrics endpoints are on an internal network.
- [ ] Every console is behind TLS; HSTS on the edge.
- [ ] Production profiles everywhere (`APP_ENV=production` for AOS, no `dev`/`test` profile for
      Prudence, `NODE_ENV=production` for Ernest).
- [ ] The two evidence keys are in the secret store and in the backups.
- [ ] Ernest has its write key, read key and OIDC set: an anonymous `POST /api/inferences`
      answers `401`.
- [ ] No `PUBLIC_ERNEST_API_KEY` in Ernest's console build.
- [ ] Machine clients have only the scopes they need (`pdp.decide`, `execution.report`).
- [ ] The governance model passes `ct_model.py check --strict`; separation of duties as your
      organization requires.
- [ ] Alerts reach real mailboxes (governance and operations).
- [ ] Images are verified before deployment (cosign; each app's `SECURITY.md`).
- [ ] A restore has been tested.
