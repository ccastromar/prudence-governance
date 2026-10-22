create table policy_audit_outbox (
  id varchar(36) primary key,
  aggregate_id varchar(36) not null,
  event_type varchar(32) not null,
  actor varchar(512) not null,
  occurred_at varchar(255) not null,
  details_json text not null,
  delivered_at varchar(255),
  dead_at varchar(255),
  attempts integer not null default 0,
  next_attempt_at varchar(255),
  last_error varchar(2000)
);
create index policy_audit_due_idx on policy_audit_outbox (next_attempt_at) where delivered_at is null and dead_at is null;
