-- Local transactional audit for policy draft creation and edits. This is not yet
-- delivered to the external evidence outbox and is not tamper-evident.
create table policy_draft_event (
  id varchar(36) primary key,
  policy_id varchar(36) not null references policy_definition(id),
  revision_id varchar(36) not null references policy_revision(id),
  event_type varchar(24) not null,
  actor varchar(512) not null,
  occurred_at timestamp with time zone not null,
  rules_json text not null
);
create index policy_draft_event_revision_idx on policy_draft_event (revision_id, occurred_at);
