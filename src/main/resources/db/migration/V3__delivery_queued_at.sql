-- When each outbound record was written, so its delivery delay is measured
-- (prudence.delivery.delay, contract/observability section 8). Null for records written before.
alter table evidence_outbox add column queued_at varchar(255);
alter table review_event add column queued_at varchar(255);
alter table attestation add column queued_at varchar(255);
alter table incident_event add column queued_at varchar(255);
alter table policy_audit_outbox add column queued_at varchar(255);
alter table alert add column queued_at varchar(255);
