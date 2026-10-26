alter table evidence_outbox add column causation_id varchar(255);
alter table evidence_outbox add column correlation_id varchar(255);
alter table evidence_outbox add column system_sequence bigint;
