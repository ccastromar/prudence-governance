-- The delivery worker asks for the oldest due records (`... order by created_at limit 500`).
-- The (delivered_at, dead_at, next_attempt_at) index finds the pending rows but not in that
-- order, so every pass sorted the whole backlog to take 500: the bigger the backlog, the slower
-- the drain. A partial index over pending rows only, in created_at order, lets a pass read just
-- the rows it returns, and stays small because delivered rows leave it.
create index evidence_outbox_due_idx on evidence_outbox (created_at)
    where delivered_at is null and dead_at is null;
