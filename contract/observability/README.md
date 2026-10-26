# Chestertown observability · conventions v1

How AOS, Prudence and Ernest describe what they do, so that one trace, one log query and one
dashboard can follow a governed run across the three. It is a convention, not a library: each
app instruments itself with OpenTelemetry and follows these names.

Status: **agreed conventions; steps 1 to 6 implemented** (section 10): objectives, burn-rate alerts and dashboards included. Section 9 says what each app does today.
Objectives for the service-level indicators (section 8) are not set here: they are agreed with
product and operations.

## 1. Principles

- **OpenTelemetry** for instrumentation and transport (OTLP); **W3C Trace Context**
  (`traceparent`, `tracestate`) for propagation. No vendor SDK in the apps: a managed backend
  (Datadog, Grafana Cloud…) is a collector exporter away.
- **RED** for every service and dependency (rate, errors, duration); **USE** for resources
  (utilisation, saturation, errors).
- **Alert on objectives, not on exceptions**: page on error-budget burn and on stuck pipelines,
  not on every failure.
- **Logs are structured JSON on stdout**; where they go is the deployment's business.
- **Bounded cardinality**: a metric attribute only ever takes a small, known set of values.
- **Private by construction**: no secrets, tokens, personal data or user content in any signal
  (section 7).

## 2. Resource attributes

Every signal of every process carries:

| Attribute | Value |
| --- | --- |
| `service.namespace` | `chestertown` |
| `service.name` | `aos`, `prudence`, `ernest-backend`, `ernest-ingestor` |
| `service.version` | The app's release (`0.1.0`), or `dev` |
| `service.instance.id` | Unique per process (container id or a UUID at start) |
| `deployment.environment.name` | `production`, `staging`, `development`… |

Set them with `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES`, so a deployment can override
them without a rebuild.

## 3. Identifiers: technical trace versus business correlation

Two different questions, two different ids.

| Id | Answers | Scope | Created by | Travels as |
| --- | --- | --- | --- | --- |
| `trace_id`, `span_id` | Which **technical execution** was this, across processes? | One distributed execution; a retry or a later delivery is a new trace, **linked** to the first | The OpenTelemetry SDK, never derived from business data | `traceparent` / `tracestate` |
| `correlation_id` | Which **business flow** does this belong to? | The whole flow, across retries, queues, outboxes and days | Whoever starts the flow: AOS's task id; Prudence's case or review for its own flows | An explicit field or header (below), persisted with every event of the flow |
| `causation_id` | Which event **caused** this one? | One step to the next | The emitter | Persisted with the event |
| Domain ids (`decision_id`, `ai_system_id`, `event_id`…) | Which thing? | — | The app that owns the thing | In bodies and records, as today |

Rules:

- A trace id is never reused as a business id, and the other way round. Search by
  `correlation_id` in logs and in Ernest's evidence to follow a flow over days; follow
  `trace_id` to see one execution's timing.
- `correlation_id` survives retries and asynchronous hops: it is stored with the outbox record,
  the journal entry and the Kafka record, and copied to the evidence in Ernest
  (`metadata.correlationId`).
- On HTTP between the apps, `correlation_id` travels as the `X-Correlation-Id` header and, where
  a contract has the field (`correlationId`), in the body. It does not travel as W3C baggage,
  which is forwarded to third parties.

## 4. Propagation

| Hop | Trace context | Correlation |
| --- | --- | --- |
| HTTP (AOS → Prudence, Prudence → Ernest, AOS or Prudence → the ingestor) | `traceparent`, `tracestate` headers; the client span is the parent of the server span | `X-Correlation-Id`, and `correlationId` in the body where the contract has it |
| Kafka (decisions, AOS's audit events) | `traceparent`, `tracestate` record headers, set when the record is produced | `correlationId` record header, and in the body's `metadata` |
| Outbox and journal (Prudence's evidence outbox, AOS's audit journal) | The `traceparent` of the transaction that wrote the record, **stored with it** | Stored with it |

Asynchronous work: the delivery or processing of a stored record is a **new span** (a new trace
when it runs later), with a **span link** to the context stored with the record. Never keep a
span open while a record waits in a queue or an outbox. A retry is a new span linked to the same
stored context; its `correlation_id` is the same.

## 5. Logs

One JSON object per line on stdout. Field names:

| Field | Content |
| --- | --- |
| `timestamp` | RFC 3339 UTC, milliseconds |
| `severity` | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL` |
| `service.name`, `service.version`, `deployment.environment.name` | As in section 2 |
| `message` | What happened, in a sentence; no user content (section 7) |
| `event.name` | A stable name for the kind of event (`evidence.delivery.dead_lettered`, `governance.review`) to query and count on |
| `trace_id`, `span_id` | When the line is written inside a span |
| `correlation_id` | When the line belongs to a business flow |
| `outcome` | `success`, `failure`, or a domain verdict from a fixed set |
| `error.type` | The error's class or a fixed code, never its free-text message alone |
| Domain ids | `decision_id`, `ai_system_id`, `event_id`… where relevant |

`INFO` is the production level. `DEBUG` may hold content for local development and is never
enabled where logs are collected.

## 6. Metrics

Names and units follow the OpenTelemetry semantic conventions where they exist, and use the app's
name as a prefix for domain metrics. Durations are histograms in seconds (`s`); counts carry a
UCUM annotation (`{record}`, `{decision}`).

**RED, every app, for its server and for each dependency it calls:**

| Metric | Attributes (bounded) |
| --- | --- |
| `http.server.request.duration` | `http.request.method`, `http.route` (the route template, never the raw path), `http.response.status_code` |
| `http.client.request.duration` | `http.request.method`, `server.address` (a dependency, from a fixed set), `http.response.status_code` |
| `messaging.process.duration`, `messaging.client.operation.duration` | `messaging.system` (`kafka`), `messaging.destination.name` (a topic, from a fixed set), `error.type` |
| `db.client.operation.duration` | `db.system.name`, `db.operation.name` |

**Domain, per app:**

| App | Metric | Attributes (bounded) |
| --- | --- | --- |
| Prudence | `prudence.decision.duration` (s) | `verdict` (`ALLOW`, `DENY`, `APPROVAL_REQUIRED`, `ERROR`), `front_door` (`decision_api`, `authzen`) |
| Prudence | `prudence.policy.evaluation.duration` (s), OPA as a dependency | `outcome` |
| Prudence | `prudence.delivery.records`, `.send`, `.due`, `.backlog.age` (existing) | `target`, `outcome` |
| Prudence | `prudence.delivery.delay` (s), written to delivered, retries included | `target` |
| Prudence | `prudence.drift.scans`, one per scheduled drift scan | `outcome` (`complete`, `incomplete`, `failed`) |
| AOS | `aos.run.duration` (s), one governed run | `outcome` (`completed`, `blocked`, `error`, `timeout`) |
| AOS | `aos.step.duration` (s), one tool call | `tool.type` (`http`, `mcp`, `a2a`, `local`), `outcome` |
| AOS | `aos.gate.duration` (s), the call to Prudence | `verdict` |
| AOS | `aos.evidence.shipped`, `aos.evidence.backlog` | `transport` (`kafka`, `http`), `outcome` |
| Ernest | `ernest.evidence.received` | `source` (`prudence-pdp`, `aos`, `api`…, from a fixed set), `outcome` |
| Ernest | `ernest.evidence.chaining.delay` (s), accepted to chained | — |
| Ernest | `ernest.proof.duration` (s), proof generation and verification | `operation` (`generate`, `verify`), `outcome` |
| Ernest | `ernest.ingestor.*`, `ernest.writer.*` (existing) | as today |

**USE, every process:** the runtime's own metrics. Prudence: Micrometer's JVM, process, HikariCP
and executor metrics. AOS: Prometheus's Go names (`go_goroutines`, `go_memstats_*`, `go_gc_*`,
`process_*`). Ernest's backend: the Node runtime instrumentation (event loop delay and
utilization, V8 heap and GC). Ernest's ingestor: the Prometheus Go client's.

**Never an attribute:** user, person, AI system, decision, task, correlation, trace or event ids;
pipeline names unless the deployment bounds them; raw paths; error messages; anything a user
typed. They belong in spans and logs.

Histogram buckets must contain the thresholds the objectives will use (section 8), so the
indicators are computed from the histograms without guessing. The buckets in use, in seconds:

| Histograms | Buckets | Indicator |
| --- | --- | --- |
| `prudence.decision.duration`, `prudence.policy.evaluation.duration`, `aos.gate.duration` | 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5 | F1 latency (the gate times out at 5 s) |
| `aos.step.duration` | 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60 | — |
| `aos.run.duration` | 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300, 600 | — |
| `ernest.evidence.chaining.delay` | 0.5, 1, 2, 5, 10, 30, 60, 120, 300, 900, 3600 | F2 freshness, F3 proof readiness |
| `ernest.proof.duration` | 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30 | F3 |

An objective's threshold T should be one of its buckets; a new threshold adds a bucket (a
compatible change: existing queries keep working).

AOS exposes its metrics on `/metrics` (scraped) with Prometheus names: `aos_run_duration_seconds`,
`aos_step_duration_seconds` (`tool_type`), `aos_gate_duration_seconds`, `aos_evidence_shipped_total`
and `aos_evidence_backlog_bytes` (journal not yet shipped, `transport`). `aos.run.duration` excludes
time waiting for a person or a callback.

Names in Prometheus follow the OTLP translation: dots become underscores, the unit becomes a
suffix and counters end in `_total` (`prudence.delivery.records` → `prudence_delivery_records_total`).

### 6.1 From a metric to its trace

Latency histograms carry **exemplars**: a bucket keeps the trace id of a recent measurement, so a
dashboard goes from a slow bucket to the trace behind it. Prudence's come from Micrometer (Spring
Boot attaches them to its OTLP histograms when tracing is on); AOS's gate, run and step
histograms add them on `/metrics` when the scraper asks for OpenMetrics. Ernest's backend has
none: the Node SDK does not support exemplars yet. Prometheus stores them with
`--enable-feature=exemplar-storage`, the collector exposes them with `enable_open_metrics`, and
Grafana links the label `trace_id` to Tempo.

### 6.2 Dashboards

`observability/dashboards/` holds the suite's Grafana dashboards: **Flows and objectives** (each
indicator of section 8: good events over the time range, error budget left over 28 days, burn
rate, error ratio by window) and **Services** (RED and domain metrics of AOS, Prudence and
Ernest, health probes and the collector). Their queries use the Prometheus names of section 6.
The local stack provisions them; a deployment copies them.

## 7. Never in any signal

Secrets, tokens, keys, passwords, cookies, `Authorization` headers; prompts, messages, parameter
values, tool inputs and outputs; names, emails, usernames or other personal identifiers (a
person, when needed, is a keyed pseudonym, as in the evidence); free text a person typed. Client
IP addresses only in access logs, under their retention.

### 7.1 Sampling

The apps record every span (head sampling at 100 %: `PRUDENCE_TRACING_SAMPLING=1.0`, the Go and
Node SDKs' default); the deployment's collector decides which traces to keep, on the whole trace
once it is complete (tail sampling). A trace is kept when any of these holds:

| Kept | Why |
| --- | --- |
| Any span with an error status | Every failure is investigated |
| Longer than 2 s | Far outside the admission objective (section 8) |
| A governed flow: a span with `correlation_id` (an AOS run), a decision at either front door, or an asynchronous hop (`aos.execution.report`, `prudence.evidence.deliver`, `ernest.kafka.process`, `ernest.events.process`) | What an investigation follows end to end; low volume |
| One in ten of the rest | Consoles, scheduled passes, reads |

So a log line's `trace_id` may name a trace that was not kept when it is outside these. Metrics
are not sampled. The local stack (`compose.observability.yaml`) keeps everything.

### 7.2 Retention and access

| Signal | Kept | Why |
| --- | --- | --- |
| Metrics | 28 days | The objectives' window (section 8); no personal data |
| Traces | 7 days | Recent executions; ids and routes only |
| Logs | 14 days | Access logs hold client IP addresses and user agents (section 7): only as long as investigating an incident needs |

Telemetry is not evidence: what an auditor relies on is in Ernest, under its own retention and
legal holds. Telemetry is read only by the suite's administrators (in the deployment, Grafana
admits the `prudence-admin` role only).

## 8. Critical flows and their indicators

Objective: **99.5 %** of valid events good, for every indicator, over a **rolling 28-day window**
(an error budget of 0.5 %: about 3 h 22 min of total outage, or 1 bad event in 200). Thresholds
T: **250 ms** for admission latency, **15 minutes** for evidence freshness and proof readiness
(posture's overdue threshold). Owners are still to be named.

| Flow | Indicator (good events / valid events) | Measured from | Exclusions |
| --- | --- | --- | --- |
| **F1. Admission of a governed run**: AOS asks Prudence | Availability: decisions answered with a verdict (200, 202, 403-with-decision) / all decision requests | `http.server.request.duration` on Prudence's decision route | 400, and 401/403 without a decision (caller's fault) |
| | Latency: decisions answered within T / decisions answered | Same histogram (and `aos.gate.duration` on AOS's side) | As above |
| **F2. Durability of evidence**: a decision, review, incident or execution recorded in Ernest | Freshness: records delivered within T of being written / records written | `prudence.delivery.backlog.age`, Kafka consumer lag, `ernest.evidence.chaining.delay` | Planned maintenance of Ernest |
| | Completeness: records never dead-lettered / records written | `prudence.delivery.records{outcome="dead"}` | — |
| **F3. Verifiability**: anyone can verify what Ernest holds | Chain checks that verify / chain checks | Ernest's `GET /api/verify`, polled | — |
| | Proof readiness: events with a proof within T of acceptance / events accepted | `ernest.evidence.chaining.delay` | — |
| **F4. Oversight on complete data**: drift and posture see every execution | Complete drift scans / drift scans | Prudence's drift checks (`complete`) | — |

How each indicator is computed in Prometheus (the error ratio is 1 − SLI; `observability/rules/slo.yml`):

| Indicator | Error ratio over a window w |
| --- | --- |
| F1 availability | decisions with `verdict="ERROR"` / all decisions (`prudence_decision_duration_seconds_count`). Requests refused before deciding (400, 401, 403 without a decision) never reach it |
| F1 latency | decisions with a verdict above 0.25 s / decisions with a verdict (`…_bucket{le="0.25"}`) |
| F2 completeness | dead-lettered / delivered + dead-lettered (`prudence_delivery_records_total`) |
| F2 freshness, F3 proof readiness | events chained after 900 s / events chained (`ernest_evidence_chaining_delay_seconds`). It starts when Ernest accepts the event; the wait in Prudence's outbox before that is `prudence_delivery_delay_seconds` (written to delivered, same buckets), on the Services dashboard and behind `EvidenceDeliveryStuck` |
| F3 verifiability | chain checks not `success` / chain checks (`ernest_proof_duration_seconds_count{operation="verify"}`) |
| F4 oversight | scheduled drift scans not `complete` (incomplete or failed) / scheduled drift scans (`prudence_drift_scans_total`, one every 15 minutes) |

Alerting is multi-window, multi-burn-rate (an alert needs both windows burning, so it fires fast
on a real burn and clears fast after it):

| Severity | Burn rate | Windows | Budget spent when it fires |
| --- | --- | --- | --- |
| page (`critical`) | 14.4 | 1 h and 5 min | 2 % in an hour |
| page (`critical`) | 6 | 6 h and 30 min | 5 % in six hours |
| ticket (`warning`) | 1 | 3 days and 6 h | 10 % in three days |

F1 and F2 page and ticket; F3 and F4 ticket only. The rules are in `observability/rules/slo.yml`
(the local stack loads them; a deployment adds the file to its Prometheus rules). The 6-hour and
3-day error ratios are recorded every 2 minutes rather than at every evaluation: they change
slowly and each one reads days of samples. With little traffic a few failures are a large
ratio: an alert also needs a minimum number of events in its long window (10 for F1 and F2,
3 for F3 and F4).

## 9. Where each app is

| | AOS | Prudence | Ernest backend | Ernest ingestor |
| --- | --- | --- | --- | --- |
| OpenTelemetry SDK | Yes (Go SDK, OTLP/HTTP; off without an endpoint) | Yes (Micrometer + OTLP) | Yes (Node SDK, OTLP; off without an endpoint) | Yes (Go SDK, OTLP/HTTP; off without an endpoint) |
| Traces | Server spans by route; `aos.plan`, `aos.run`, `aos.step`; calls to Prudence continue the trace; the execution report is a linked span | Server spans; continues incoming traces; outbox delivery is a new span linked to the trace that wrote each record | HTTP, Express, Nest and MongoDB spans; continues incoming traces | Server spans; worker and Kafka processing are new spans linked to the sender's context |
| Trace context stored for asynchronous hops | Each journal entry keeps its `traceparent`; sent as a Kafka header and over HTTP | Each outbound record keeps its `traceparent` (V2); the delivery's context goes as a Kafka header | — | Stored with each stream entry |
| `correlation_id` end to end | The task id: `X-Correlation-Id` and `correlationId` to Prudence; `metadata.correlationId` and a `correlationId` header in the evidence | Decision API `correlationId` or `X-Correlation-Id`: stored on the decision, in its logs (`correlation_id`), and sent with its evidence (Kafka header, HTTP header, metadata) | Kept in evidence metadata | Kept in evidence metadata |
| Logs (section 5) | JSON by default outside development (`LOG_FORMAT`); a task's lines carry `correlation_id`, `trace_id`, `span_id`; `LOG_LEVEL` applied | JSON by default (`ChestertownLogFormat`), text in the `dev` profile; MDC trace, span and correlation; named events for delivery | JSON with `NODE_ENV=production` (`LOG_FORMAT`); active span's trace and `X-Correlation-Id` | JSON by default (`LOG_FORMAT`); failures `WARN`/`ERROR` with `error.type`; processing lines carry `trace_id` |
| Runtime (USE) | Go runtime and process | JVM, process, pools | Node event loop, heap, GC | Go runtime and process |
| RED metrics | Own HTTP counters (`aos_http_*`), routes normalised | Yes (Micrometer `http.server.requests`, seconds) | Yes (`http.server.request.duration` over OTLP) | Ingestion counters |
| Domain metrics (section 6) | Run, step and gate histograms; evidence shipped and backlog | Decision and policy evaluation histograms; delivery pipeline | Evidence received, chaining delay, proof duration | Ingestion and writer counters |

**Checked end to end** by `scripts/e2e-suite.sh` (group `observability`), with every app
exporting to `scripts/otlp-sink`: an AOS log line gives the run's `trace_id`; that trace holds
AOS's and Prudence's spans; the execution report, Prudence's evidence delivery and Ernest's Kafka
processing are new traces linked to it; Ernest holds the AOS run and the Prudence decision under
the same `correlationId`; the decision, chaining and gate histograms have the buckets of section
6. `compose.observability.yaml` runs the collector, Tempo, Prometheus and Grafana locally.

AOS no longer derives a trace id from its task id: the task id is the correlation id, and trace
context comes from the SDK (section 3).

## 10. Order of work

1. This document.
2. AOS and Ernest adopt the OpenTelemetry SDK; trace context over HTTP, Kafka and the outbox and
   journal; `correlationId` added to the Decision API v1 as an optional field.
3. JSON logs with the fields of section 5 in the three apps.
4. The domain metrics of section 6, with buckets for section 8.
5. A local profile of the observability stack and a test of the whole flow: from a metric or a
   log to the trace, and the `correlation_id` across the asynchronous hops.
6. Dashboards per service and per flow; burn-rate alerts once objectives are agreed.

## 11. Changing it

A change to a name, an attribute or a field that dashboards or alerts use is a new version of
this document (`v2`), with the old name kept alongside for a suite release.
