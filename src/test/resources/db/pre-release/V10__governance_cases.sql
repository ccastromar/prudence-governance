-- Governance dossiers only retain process metadata and verifiable references. Document bytes
-- remain in their source systems (DMS, GRC, Git, ticketing) and are addressed by URI + SHA-256.
create table governance_case (
 id varchar(255) primary key, ai_system_id varchar(255) not null references ai_system(id),
 type varchar(255) not null check (type in ('ASSESSMENT','CHANGE','EXCEPTION','CORRECTIVE_ACTION')),
 status varchar(255) not null check (status in ('OPEN','IN_REVIEW','CLOSED')),
 title varchar(500) not null, summary varchar(4000) not null, owner varchar(255) not null,
 opened_by varchar(255) not null, opened_at varchar(255) not null, review_started_at varchar(255),
 closed_at varchar(255), closed_by varchar(255), closing_decision varchar(4000)
);
create table governance_case_evidence (
 id varchar(255) primary key, case_id varchar(255) not null references governance_case(id), label varchar(500) not null,
 uri varchar(2000) not null, sha256 varchar(255) not null, version varchar(255) not null, owner varchar(255) not null,
 added_by varchar(255) not null, added_at varchar(255) not null
);
create table governance_case_event (
 id varchar(255) primary key, case_id varchar(255) not null references governance_case(id), type varchar(255) not null,
 actor varchar(255) not null, at varchar(255) not null, details varchar(4000) not null
);
create index governance_case_system_idx on governance_case(ai_system_id);
create index governance_case_event_case_idx on governance_case_event(case_id, at);
