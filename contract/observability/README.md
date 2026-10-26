# Chestertown observability · conventions v1

How AOS, Prudence and Ernest describe what they do, so that one trace, one log query and one
dashboard can follow a governed run across the three. It is a convention, not a library: each
app instruments itself with OpenTelemetry and follows these names.

Status: **agreed conventions, partly implemented**. Section 9 says what each app does today.
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
| AOS | `aos.run.duration` (s), one governed run | `outcome` (`completed`, `blocked`, `error`, `timeout`) |
| AOS | `aos.step.duration` (s), one tool call | `tool.type` (`http`, `mcp`, `a2a`, `local`), `outcome` |
| AOS | `aos.gate.duration` (s), the call to Prudence | `verdict` |
| AOS | `aos.evidence.shipped`, `aos.evidence.backlog` | `transport` (`kafka`, `http`), `outcome` |
| Ernest | `ernest.evidence.received` | `source` (`prudence-pdp`, `aos`, `api`…, from a fixed set), `outcome` |
| Ernest | `ernest.evidence.chaining.delay` (s), accepted to chained | — |
| Ernest | `ernest.proof.duration` (s), proof generation and verification | `operation` (`generate`, `verify`), `outcome` |
| Ernest | `ernest.ingestor.*`, `ernest.writer.*` (existing) | as today |

**Never an attribute:** user, person, AI system, decision, task, correlation, trace or event ids;
pipeline names unless the deployment bounds them; raw paths; error messages; anything a user
typed. They belong in spans and logs.

Histogram buckets must contain the thresholds the objectives will use (section 8), so the
indicators are computed from the histograms without guessing.

Names in Prometheus follow the OTLP translation: dots become underscores, the unit becomes a
suffix and counters end in `_total` (`prudence.delivery.records` → `prudence_delivery_records_total`).

## 7. Never in any signal

Secrets, tokens, keys, passwords, cookies, `Authorization` headers; prompts, messages, parameter
values, tool inputs and outputs; names, emails, usernames or other personal identifiers (a
person, when needed, is a keyed pseudonym, as in the evidence); free text a person typed. Client
IP addresses only in access logs, under their retention.

## 8. Critical flows and their indicators

Objectives, windows and owners are to be agreed; the indicators are what they will be measured
on. Suggested window: 28 days, rolling.

| Flow | Indicator (good events / valid events) | Measured from | Exclusions |
| --- | --- | --- | --- |
| **F1. Admission of a governed run**: AOS asks Prudence | Availability: decisions answered with a verdict (200, 202, 403-with-decision) / all decision requests | `http.server.request.duration` on Prudence's decision route | 400, and 401/403 without a decision (caller's fault) |
| | Latency: decisions answered within T / decisions answered | Same histogram (and `aos.gate.duration` on AOS's side) | As above |
| **F2. Durability of evidence**: a decision, review, incident or execution recorded in Ernest | Freshness: records delivered within T of being written / records written | `prudence.delivery.backlog.age`, Kafka consumer lag, `ernest.evidence.chaining.delay` | Planned maintenance of Ernest |
| | Completeness: records never dead-lettered / records written | `prudence.delivery.records{outcome="dead"}` | — |
| **F3. Verifiability**: anyone can verify what Ernest holds | Chain checks that verify / chain checks | Ernest's `GET /api/verify`, polled | — |
| | Proof readiness: events with a proof within T of acceptance / events accepted | `ernest.evidence.chaining.delay` | — |
| **F4. Oversight on complete data**: drift and posture see every execution | Complete drift scans / drift scans | Prudence's drift checks (`complete`) | — |

Alerting, once objectives exist: multi-window burn-rate alerts on F1 and F2; ticket-level alerts
on F3 and F4. Until then, the availability and backlog alerts in the deployment stand in.

## 9. Where each app is

| | AOS | Prudence | Ernest backend | Ernest ingestor |
| --- | --- | --- | --- | --- |
| OpenTelemetry SDK | No | Yes (Micrometer + OTLP) | No | No |
| Traces | Sends `traceparent` to Prudence derived from the task id (**transitional**, see below) | Server spans; continues incoming traces | No | No |
| `correlation_id` end to end | Task id in the journal and evidence (`traceId` field) | Decision API `correlationId` or `X-Correlation-Id`: stored on the decision, in its logs (`correlation_id`), and sent with its evidence (Kafka header, HTTP header, metadata) | Kept in evidence metadata when sent | Passes it through |
| Logs | Text, plus one JSON line per HTTP request | JSON (logstash format) when the overlay sets it | Text (Nest logger) | Text |
| RED metrics | Own HTTP counters (`aos_http_*`), routes normalised | Yes (Micrometer `http.server.requests`) | No | Ingestion counters |
| Domain metrics | Bus and LLM counters | Delivery pipeline | No | Ingestion and writer counters |

**Transitional:** AOS currently sends a `traceparent` whose trace id is its task id (a UUID).
That ties Prudence's spans to the task without an SDK, but makes a business id a trace id, which
section 3 rules out. It is replaced when AOS adopts the OpenTelemetry SDK: a real trace context,
and the task id as `correlation_id`.

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
