-- Prudence owns the state of a governance control; the referenced evidence remains in its
-- authoritative repository and is identified by its immutable SHA-256 digest.
create table compliance_control (
    id varchar(255) primary key,
    ai_system_id varchar(255) not null references ai_system(id),
    profile_id varchar(255) not null,
    control_key varchar(255) not null,
    title varchar(255) not null,
    requirement varchar(4000) not null,
    blocking boolean not null,
    status varchar(255) not null check (status in ('PENDING', 'SATISFIED')),
    evidence_uri varchar(2000),
    evidence_sha256 varchar(255),
    evidence_version varchar(255),
    evidence_owner varchar(255),
    satisfied_at varchar(255),
    expires_at varchar(255),
    created_at varchar(255) not null,
    unique (ai_system_id, profile_id, control_key)
);

create index compliance_control_system_idx on compliance_control (ai_system_id, profile_id, control_key);
