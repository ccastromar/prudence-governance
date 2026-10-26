-- Foundation for a future policy control plane. These records are not consumed by the
-- current PDP or OPA; the existing file-based policy remains authoritative.
create table policy_definition (
  id varchar(36) primary key,
  name varchar(200) not null,
  description text not null default '',
  created_by varchar(512) not null,
  created_at timestamp with time zone not null,
  constraint policy_definition_name_uk unique (name)
);

-- Revisions are snapshots, never updates to an active policy. The application must enforce
-- the lifecycle, reviewer separation and optimistic locking before exposing this storage.
create table policy_revision (
  id varchar(36) primary key,
  policy_id varchar(36) not null references policy_definition(id),
  revision_number integer not null check (revision_number > 0),
  status varchar(24) not null check (status in ('DRAFT', 'VALIDATED', 'APPROVED', 'DISTRIBUTING', 'ACTIVE', 'FAILED', 'SUPERSEDED')),
  rule_schema_version integer not null check (rule_schema_version > 0),
  rules_json text not null,
  created_by varchar(512) not null,
  created_at timestamp with time zone not null,
  approved_by varchar(512),
  approved_at timestamp with time zone,
  version bigint not null default 0,
  constraint policy_revision_number_uk unique (policy_id, revision_number),
  constraint policy_revision_approval_pair check ((approved_by is null) = (approved_at is null)),
  constraint policy_revision_separate_approver check (approved_by is null or approved_by <> created_by)
);
create index policy_revision_status_idx on policy_revision (status);
