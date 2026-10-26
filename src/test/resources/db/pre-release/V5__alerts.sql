-- Alerts: raised in the same transaction as the event that causes them, then emailed by the
-- delivery worker (same delivery columns as the evidence outbox). dedup_key makes raising
-- idempotent across sweeps, retries, restarts and instances.
create table alert (
    id varchar(255) not null,
    kind varchar(255) check (kind in ('REVIEW_DUE','APPROVAL_EXPIRED','DRIFT_VIOLATION','EVIDENCE_DEAD_LETTERED','DELIVERY_CONFIGURATION')),
    dedup_key varchar(255) unique,
    ai_system_id varchar(255),
    recipients varchar(2000),
    subject varchar(500),
    body varchar(4000),
    created_at varchar(255),
    delivered_at varchar(255),
    dead_at varchar(255),
    attempts integer not null default 0,
    next_attempt_at varchar(255),
    last_error varchar(2000),
    primary key (id)
);

create index alert_pending_idx on alert (delivered_at, dead_at, next_attempt_at);
