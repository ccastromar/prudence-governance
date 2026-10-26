-- The trace context of the work that wrote each outbound record (contract/observability,
-- section 4): its delivery is a new span linked to it. Null for records written before, or
-- outside a trace.
alter table evidence_outbox add column traceparent varchar(55);
alter table review_event add column traceparent varchar(55);
alter table attestation add column traceparent varchar(55);
alter table incident_event add column traceparent varchar(55);
alter table policy_audit_outbox add column traceparent varchar(55);
alter table alert add column traceparent varchar(55);
