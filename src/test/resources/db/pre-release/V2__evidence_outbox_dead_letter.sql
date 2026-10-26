-- Dead-letter state for the evidence outbox: events Ernest rejected permanently, or that ran
-- out of attempts, stop retrying and are surfaced in posture instead of silently looping.
alter table evidence_outbox add column dead_at varchar(255);

drop index evidence_outbox_pending_idx;
create index evidence_outbox_pending_idx on evidence_outbox (delivered_at, dead_at, next_attempt_at);
create index evidence_outbox_system_idx on evidence_outbox (ai_system_id);
