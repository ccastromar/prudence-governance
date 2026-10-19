-- The AI system's name and risk tier as they stood when the decision was taken, so the
-- evidence sent later carries the system's identity (Prudence ADR-001), not today's values.
alter table evidence_outbox add column ai_system_name varchar(255);
alter table evidence_outbox add column risk_tier varchar(32);
