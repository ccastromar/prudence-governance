-- Review events and attestations become their own evidence outbox: the same delivery columns
-- as evidence_outbox (see com.prudence.evidence.delivery.Delivery), so a failed seal is
-- retried in the background instead of staying unsealed forever.
alter table review_event add column delivered_at varchar(255);
alter table review_event add column dead_at varchar(255);
alter table review_event add column attempts integer not null default 0;
alter table review_event add column next_attempt_at varchar(255);
alter table review_event add column last_error varchar(2000);

alter table attestation add column delivered_at varchar(255);
alter table attestation add column dead_at varchar(255);
alter table attestation add column attempts integer not null default 0;
alter table attestation add column next_attempt_at varchar(255);
alter table attestation add column last_error varchar(2000);

-- Backfill. A record Ernest already acknowledged (qualified seal or a block index) counts as
-- delivered. Anything else was never confirmed and is queued now, under its original id.
update review_event set delivered_at = at where sealed or block_index is not null;
update review_event set next_attempt_at = '1970-01-01T00:00:00.000Z' where delivered_at is null;
update attestation set delivered_at = generated_at where sealed or block_index is not null;
update attestation set next_attempt_at = '1970-01-01T00:00:00.000Z' where delivered_at is null;

create index review_event_pending_idx on review_event (delivered_at, dead_at, next_attempt_at);
create index attestation_pending_idx on attestation (delivered_at, dead_at, next_attempt_at);
