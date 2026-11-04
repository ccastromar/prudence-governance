-- Who asked, who acted and for whom (contract/identity/ section 11). `subject` stays the agent
-- that acted. Null for decisions recorded before: those callers acted for themselves.
alter table evidence_outbox add column caller varchar(512);
alter table evidence_outbox add column agent_assurance varchar(32);
alter table evidence_outbox add column on_behalf_of varchar(512);
alter table evidence_outbox add column on_behalf_of_assurance varchar(32);
alter table evidence_outbox add column reason_code varchar(64);
-- The agent an execution report names (a gateway reporting for the agent behind it); null when
-- the reporting workload ran it itself.
alter table execution_report add column agent varchar(512);
