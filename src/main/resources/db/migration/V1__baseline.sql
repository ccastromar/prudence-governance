-- Baseline schema for Prudence (PostgreSQL). Generated from the JPA mappings with the
-- PostgreSQL dialect; Hibernate runs with ddl-auto=validate against it in production.
-- Change the schema only through new versioned migrations (V2__..., V3__...).


create table ai_system (
    annexi boolean not null,
    annexiii boolean not null,
    classification_justification varchar(4000),
    obligations varchar(4000),
    affected_population varchar(255),
    approval_expires_at varchar(255),
    approval_status varchar(255) check ((approval_status in ('DRAFT','UNDER_ASSESSMENT','EVIDENCE_PENDING','PENDING_APPROVAL','PENDING_SECOND_APPROVAL','APPROVED','REJECTED','BLOCKED','SUSPENDED','RETIRED'))),
    approved_at varchar(255),
    assessed_at varchar(255),
    first_approved_at varchar(255),
    first_approver varchar(255),
    id varchar(255) not null,
    intended_purpose varchar(255),
    model_hash varchar(255),
    name varchar(255),
    operating_geography varchar(255),
    owner_email varchar(255),
    owner_name varchar(255),
    regulatory_role varchar(255) check ((regulatory_role in ('PROVIDER','DEPLOYER','IMPORTER','DISTRIBUTOR'))),
    risk_tier varchar(255) check ((risk_tier in ('PROHIBITED','HIGH','LIMITED','MINIMAL'))),
    system_version varchar(255),
    primary key (id)
);

create table attestation (
    sealed boolean not null,
    block_index bigint,
    report_json varchar(1000000),
    content_hash varchar(255),
    generated_at varchar(255),
    id varchar(255) not null,
    model_id varchar(255),
    overall_posture varchar(255),
    seal_detail varchar(255),
    primary key (id)
);

create table evidence_outbox (
    attempts integer not null,
    last_error varchar(2000),
    reason varchar(2000),
    action varchar(255),
    ai_system_id varchar(255),
    created_at varchar(255),
    delivered_at varchar(255),
    id varchar(255) not null,
    model_hash varchar(255),
    next_attempt_at varchar(255),
    pipeline varchar(255),
    policy_hash varchar(255),
    policy_version varchar(255),
    subject varchar(255),
    system_version varchar(255),
    verdict varchar(255),
    primary key (id)
);

create table review_event (
    sealed boolean not null,
    block_index bigint,
    reason varchar(2000),
    action varchar(255),
    ai_system_id varchar(255),
    at varchar(255),
    id varchar(255) not null,
    new_status varchar(255),
    previous_status varchar(255),
    reviewer varchar(255),
    primary key (id)
);

-- Outbox worker polls undelivered rows due for an attempt; audit trail reads per system.
create index evidence_outbox_pending_idx on evidence_outbox (delivered_at, next_attempt_at);
create index review_event_system_idx on review_event (ai_system_id);
