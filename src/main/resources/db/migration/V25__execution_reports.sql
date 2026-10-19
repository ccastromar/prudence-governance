-- What runtimes report they executed, with the decision that allowed it (Prudence ADR-001,
-- Decision API v1). A second execution source for drift, so drift works without Ernest.
-- No foreign key to ai_system: an execution of an unregistered system is exactly what drift
-- must be able to see.
create table execution_report (
  id varchar(255) primary key,
  runtime varchar(255) not null,
  execution_id varchar(255) not null,
  decision_id varchar(255),
  decision_status varchar(32) not null,
  ai_system_id varchar(255) not null,
  pipeline varchar(255) not null,
  started_at varchar(255) not null,
  reported_by varchar(255) not null,
  received_at varchar(255) not null,
  constraint execution_report_runtime_execution_uk unique (runtime, execution_id)
);
create index execution_report_system_idx on execution_report (ai_system_id, started_at);
