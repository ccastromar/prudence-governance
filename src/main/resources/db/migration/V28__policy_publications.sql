-- The channel serializes publishers across Prudence instances. Publications are immutable;
-- a channel pointer represents the requested revision, not proof of OPA activation.
create table policy_publication (
  id varchar(36) primary key,
  digest varchar(71) not null,
  bundle bytea not null,
  revisions_json text not null,
  created_by varchar(512) not null,
  created_at timestamp with time zone not null,
  rollback_of varchar(36) references policy_publication(id)
);
create table policy_publication_channel (
  id integer primary key check (id = 1),
  publication_id varchar(36) references policy_publication(id)
);
insert into policy_publication_channel (id, publication_id) values (1, null);

-- Observations are made from a verified decision, not from delivery or a separate status check.
create table policy_opa_observation (
  instance_id varchar(255) primary key,
  publication_id varchar(36) not null references policy_publication(id),
  digest varchar(71) not null,
  observed_at timestamp with time zone not null
);
