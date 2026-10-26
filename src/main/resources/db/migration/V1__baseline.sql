-- Prudence's data model, version 1 (PostgreSQL), the baseline of the first public release. It is
-- exactly the schema the pre-release migrations built (V1..V31, kept in
-- src/test/resources/db/pre-release): PostgresMigrationsTest compares the two catalogs, names of
-- constraints and indexes included. Hibernate validates the entities against it at every start.
--
-- Change it only through new versioned migrations (V2__...), never by editing this file.
-- A database migrated by the pre-release migrations is adopted at start (PreReleaseAdoption).

-- AI system registry and reviews

create table ai_system (
    annexi boolean not null,
    annexiii boolean not null,
    classification_justification varchar(4000),
    obligations varchar(4000),
    affected_population varchar(255),
    approval_expires_at varchar(255),
    approval_status varchar(255),
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
    regulatory_role varchar(255),
    risk_tier varchar(255),
    system_version varchar(255),
    renewal_first_approver varchar(255),
    renewal_first_approved_at varchar(255),
    review_due_notified_at varchar(255),
    declared_by varchar(512),
    submitted_by varchar(512),
    constraint ai_system_approval_status_check check (approval_status in ('DRAFT', 'UNDER_ASSESSMENT', 'EVIDENCE_PENDING', 'PENDING_APPROVAL', 'PENDING_SECOND_APPROVAL', 'APPROVED', 'EXPIRED', 'REJECTED', 'BLOCKED', 'SUSPENDED', 'RETIRED')),
    constraint ai_system_regulatory_role_check check (regulatory_role in ('PROVIDER', 'DEPLOYER', 'IMPORTER', 'DISTRIBUTOR')),
    constraint ai_system_risk_tier_check check (risk_tier in ('PROHIBITED', 'HIGH', 'LIMITED', 'MINIMAL')),
    constraint ai_system_pkey primary key (id)
);
create index ai_system_status_idx on ai_system (approval_status);

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
    delivered_at varchar(255),
    dead_at varchar(255),
    attempts integer default 0 not null,
    next_attempt_at varchar(255),
    last_error varchar(2000),
    constraint review_event_pkey primary key (id)
);
create index review_event_pending_idx on review_event (delivered_at, dead_at, next_attempt_at);
create index review_event_system_idx on review_event (ai_system_id);

create table system_event_sequence (
    ai_system_id varchar(255) not null,
    next_sequence bigint not null,
    constraint system_event_sequence_pkey primary key (ai_system_id)
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
    delivered_at varchar(255),
    dead_at varchar(255),
    attempts integer default 0 not null,
    next_attempt_at varchar(255),
    last_error varchar(2000),
    constraint attestation_pkey primary key (id)
);
create index attestation_pending_idx on attestation (delivered_at, dead_at, next_attempt_at);

create table compliance_snapshot (
    id varchar(255) not null,
    ai_system_id varchar(255) not null,
    as_of varchar(255) not null,
    sha256 varchar(255) not null,
    manifest varchar(20000) not null,
    constraint compliance_snapshot_pkey primary key (id)
);

-- Policy decisions, execution reports and evidence delivery

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
    subject varchar(512),
    system_version varchar(255),
    verdict varchar(255),
    dead_at varchar(255),
    causation_id varchar(255),
    correlation_id varchar(255),
    system_sequence bigint,
    ai_system_name varchar(255),
    risk_tier varchar(32),
    constraint evidence_outbox_pkey primary key (id)
);
create index evidence_outbox_due_idx on evidence_outbox (created_at) where ((delivered_at is null) and (dead_at is null));
create index evidence_outbox_pending_idx on evidence_outbox (delivered_at, dead_at, next_attempt_at);
create index evidence_outbox_system_idx on evidence_outbox (ai_system_id);

create table execution_report (
    id varchar(255) not null,
    runtime varchar(255) not null,
    execution_id varchar(255) not null,
    decision_id varchar(255),
    decision_status varchar(32) not null,
    ai_system_id varchar(255) not null,
    pipeline varchar(255) not null,
    started_at varchar(255) not null,
    reported_by varchar(255) not null,
    received_at varchar(255) not null,
    constraint execution_report_pkey primary key (id),
    constraint execution_report_runtime_execution_uk unique (runtime, execution_id)
);
create index execution_report_system_idx on execution_report (ai_system_id, started_at);

-- Compliance controls, impact and transparency

create table compliance_control (
    id varchar(255) not null,
    ai_system_id varchar(255) not null,
    profile_id varchar(255) not null,
    control_key varchar(255) not null,
    title varchar(255) not null,
    requirement varchar(4000) not null,
    blocking boolean not null,
    status varchar(255) not null,
    evidence_uri varchar(2000),
    evidence_sha256 varchar(255),
    evidence_version varchar(255),
    evidence_owner varchar(255),
    satisfied_at varchar(255),
    expires_at varchar(255),
    created_at varchar(255) not null,
    accepts varchar(1000) default 'URL,SHA-256,version,owner' not null,
    expires_after_days integer,
    expires_on_version_change boolean default false not null,
    blocks_version_change boolean default false not null,
    evidence_source varchar(255) default 'MANUAL_OR_GRC' not null,
    evidence_dataset_version varchar(255),
    evidence_metrics varchar(4000),
    evidence_approver varchar(255),
    evidence_attestation varchar(4000),
    ernest_receipt varchar(2000),
    constraint compliance_control_status_check check (status in ('NOT_STARTED', 'EVIDENCE_REQUESTED', 'SATISFIED', 'EXPIRED', 'FAILED', 'WAIVED')),
    constraint compliance_control_ai_system_id_profile_id_control_key_key unique (ai_system_id, profile_id, control_key),
    constraint compliance_control_pkey primary key (id)
);
create index compliance_control_system_idx on compliance_control (ai_system_id, profile_id, control_key);

create table impact_requirement (
    ai_system_id varchar(255) not null,
    fria_required boolean default false not null,
    transparency_required boolean default false not null,
    constraint impact_requirement_pkey primary key (ai_system_id)
);

create table fria_assessment (
    id varchar(255) not null,
    ai_system_id varchar(255) not null,
    jurisdiction varchar(255) not null,
    template_version varchar(255) not null,
    affected_groups varchar(4000) not null,
    assumptions varchar(4000) not null,
    mitigations varchar(4000) not null,
    dpia_uri varchar(2000) not null,
    dpia_sha256 varchar(255) not null,
    signed_by varchar(255) not null,
    signed_at varchar(255) not null,
    constraint fria_assessment_pkey primary key (id)
);

create table transparency_notice (
    id varchar(255) not null,
    ai_system_id varchar(255) not null,
    kind varchar(255) not null,
    audience varchar(1000) not null,
    notice_version varchar(255) not null,
    deployment_evidence varchar(2000) not null,
    deployed_by varchar(255) not null,
    deployed_at varchar(255) not null,
    constraint transparency_notice_pkey primary key (id)
);

-- Governance cases and change assessments

create table governance_case (
    id varchar(255) not null,
    ai_system_id varchar(255) not null,
    type varchar(255) not null,
    status varchar(255) not null,
    title varchar(500) not null,
    summary varchar(4000) not null,
    owner varchar(255) not null,
    opened_by varchar(255) not null,
    opened_at varchar(255) not null,
    review_started_at varchar(255),
    closed_at varchar(255),
    closed_by varchar(255),
    closing_decision varchar(4000),
    constraint governance_case_status_check check (status in ('OPEN', 'IN_REVIEW', 'CLOSED')),
    constraint governance_case_type_check check (type in ('ASSESSMENT', 'CHANGE', 'EXCEPTION', 'CORRECTIVE_ACTION')),
    constraint governance_case_pkey primary key (id)
);
create index governance_case_system_idx on governance_case (ai_system_id);

create table governance_case_event (
    id varchar(255) not null,
    case_id varchar(255) not null,
    type varchar(255) not null,
    actor varchar(255) not null,
    at varchar(255) not null,
    details varchar(4000) not null,
    constraint governance_case_event_pkey primary key (id)
);
create index governance_case_event_case_idx on governance_case_event (case_id, at);

create table governance_case_evidence (
    id varchar(255) not null,
    case_id varchar(255) not null,
    label varchar(500) not null,
    uri varchar(2000) not null,
    sha256 varchar(255) not null,
    version varchar(255) not null,
    owner varchar(255) not null,
    added_by varchar(255) not null,
    added_at varchar(255) not null,
    constraint governance_case_evidence_pkey primary key (id)
);

create table change_assessment (
    id varchar(255) not null,
    ai_system_id varchar(255) not null,
    proposed_version varchar(255) not null,
    changed_fields varchar(4000),
    outcome varchar(255) not null,
    rationale varchar(4000) not null,
    evidence_uri varchar(2000) not null,
    evidence_sha256 varchar(255) not null,
    assessed_at varchar(255) not null,
    assessed_by varchar(255) not null,
    constraint change_assessment_outcome_check check (outcome in ('MINOR', 'CONTROL_REVIEW', 'SUBSTANTIAL', 'PROHIBITED')),
    constraint change_assessment_pkey primary key (id)
);
create index change_assessment_system_idx on change_assessment (ai_system_id, assessed_at);

-- Human oversight

create table human_oversight_assignment (
    id varchar(255) not null,
    ai_system_id varchar(255) not null,
    role varchar(255) not null,
    person varchar(255) not null,
    override_authority boolean not null,
    stop_authority boolean not null,
    training_evidence varchar(2000) not null,
    training_expires_at varchar(255) not null,
    constraint human_oversight_assignment_pkey primary key (id)
);

create table human_override (
    id varchar(255) not null,
    ai_system_id varchar(255) not null,
    aos_reference varchar(255) not null,
    supervisor varchar(255) not null,
    reason varchar(4000) not null,
    outcome varchar(4000) not null,
    occurred_at varchar(255) not null,
    reviewed_by varchar(255),
    reviewed_at varchar(255),
    review_note varchar(4000),
    constraint human_override_pkey primary key (id)
);

-- Monitoring and corrective action

create table monitoring_signal (
    id varchar(255) not null,
    ai_system_id varchar(255) not null,
    signal varchar(255) not null,
    value double precision not null,
    amber_threshold double precision not null,
    red_threshold double precision not null,
    status varchar(255) not null,
    observed_at varchar(255) not null,
    source_reference varchar(2000),
    constraint monitoring_signal_pkey primary key (id)
);

create table capa (
    id varchar(255) not null,
    ai_system_id varchar(255) not null,
    signal_id varchar(255) not null,
    risk varchar(4000) not null,
    mitigation varchar(4000) not null,
    owner varchar(255) not null,
    due_at varchar(255) not null,
    status varchar(255) not null,
    effectiveness_evidence varchar(4000),
    closed_at varchar(255),
    constraint capa_pkey primary key (id)
);

-- Incident register (AI Act Art. 73)

create table incident (
    id varchar(255) not null,
    ai_system_id varchar(255),
    title varchar(500),
    description varchar(4000),
    occurred_at varchar(255),
    detected_at varchar(255),
    reported_by varchar(255),
    regulatory_role varchar(255),
    member_states varchar(255),
    category varchar(255),
    widespread_infringement boolean not null,
    causal_link varchar(255),
    status varchar(255),
    initial_report_recipient varchar(255),
    initial_report_reference varchar(255),
    initial_report_submitted_at varchar(255),
    initial_report_recorded_by varchar(255),
    final_report_recipient varchar(255),
    final_report_reference varchar(255),
    final_report_submitted_at varchar(255),
    final_report_recorded_by varchar(255),
    root_cause varchar(4000),
    corrective_actions varchar(4000),
    closed_at varchar(255),
    closed_by varchar(255),
    closing_note varchar(2000),
    constraint incident_category_check check (category in ('DEATH', 'SERIOUS_HARM_TO_HEALTH', 'CRITICAL_INFRASTRUCTURE_DISRUPTION', 'FUNDAMENTAL_RIGHTS_INFRINGEMENT', 'PROPERTY_OR_ENVIRONMENT_HARM', 'NOT_SERIOUS')),
    constraint incident_causal_link_check check (causal_link in ('SUSPECTED', 'ESTABLISHED', 'RULED_OUT')),
    constraint incident_regulatory_role_check check (regulatory_role in ('PROVIDER', 'DEPLOYER', 'IMPORTER', 'DISTRIBUTOR')),
    constraint incident_status_check check (status in ('OPEN', 'INITIAL_REPORT_SUBMITTED', 'FINAL_REPORT_SUBMITTED', 'CLOSED')),
    constraint incident_pkey primary key (id)
);
create index incident_status_idx on incident (status);
create index incident_system_idx on incident (ai_system_id);

create table incident_event (
    id varchar(255) not null,
    incident_id varchar(255),
    ai_system_id varchar(255),
    type varchar(255),
    actor varchar(255),
    at varchar(255),
    details varchar(4000),
    sealed boolean not null,
    block_index bigint,
    delivered_at varchar(255),
    dead_at varchar(255),
    attempts integer default 0 not null,
    next_attempt_at varchar(255),
    last_error varchar(2000),
    constraint incident_event_pkey primary key (id)
);
create index incident_event_incident_idx on incident_event (incident_id);
create index incident_event_pending_idx on incident_event (delivered_at, dead_at, next_attempt_at);

-- Alerts

create table alert (
    id varchar(255) not null,
    kind varchar(255),
    dedup_key varchar(255),
    ai_system_id varchar(255),
    recipients varchar(2000),
    subject varchar(500),
    body varchar(4000),
    created_at varchar(255),
    delivered_at varchar(255),
    dead_at varchar(255),
    attempts integer default 0 not null,
    next_attempt_at varchar(255),
    last_error varchar(2000),
    constraint alert_kind_check check (kind in ('REVIEW_DUE', 'APPROVAL_EXPIRED', 'DRIFT_VIOLATION', 'EVIDENCE_DEAD_LETTERED', 'DELIVERY_CONFIGURATION', 'SERIOUS_INCIDENT', 'INCIDENT_REPORT_DUE', 'INCIDENT_REPORT_OVERDUE', 'DRIFT_SCAN_INCOMPLETE')),
    constraint alert_dedup_key_key unique (dedup_key),
    constraint alert_pkey primary key (id)
);
create index alert_pending_idx on alert (delivered_at, dead_at, next_attempt_at);

-- Policy control plane

create table policy_definition (
    id varchar(36) not null,
    name varchar(200) not null,
    description text default '' not null,
    created_by varchar(512) not null,
    created_at timestamptz not null,
    constraint policy_definition_name_uk unique (name),
    constraint policy_definition_pkey primary key (id)
);

create table policy_revision (
    id varchar(36) not null,
    policy_id varchar(36) not null,
    revision_number integer not null,
    status varchar(24) not null,
    rule_schema_version integer not null,
    rules_json text not null,
    created_by varchar(512) not null,
    created_at timestamptz not null,
    approved_by varchar(512),
    approved_at timestamptz,
    version bigint default 0 not null,
    constraint policy_revision_approval_pair check (((approved_by is null) = (approved_at is null))),
    constraint policy_revision_revision_number_check check ((revision_number > 0)),
    constraint policy_revision_rule_schema_version_check check ((rule_schema_version > 0)),
    constraint policy_revision_separate_approver check (((approved_by is null) or (approved_by <> created_by))),
    constraint policy_revision_status_check check (status in ('DRAFT', 'VALIDATED', 'APPROVED', 'DISTRIBUTING', 'ACTIVE', 'FAILED', 'SUPERSEDED')),
    constraint policy_revision_number_uk unique (policy_id, revision_number),
    constraint policy_revision_pkey primary key (id)
);
create index policy_revision_status_idx on policy_revision (status);

create table policy_draft_event (
    id varchar(36) not null,
    policy_id varchar(36) not null,
    revision_id varchar(36) not null,
    event_type varchar(24) not null,
    actor varchar(512) not null,
    occurred_at timestamptz not null,
    rules_json text not null,
    constraint policy_draft_event_pkey primary key (id)
);
create index policy_draft_event_revision_idx on policy_draft_event (revision_id, occurred_at);

create table policy_publication (
    id varchar(36) not null,
    digest varchar(71) not null,
    bundle bytea not null,
    revisions_json text not null,
    created_by varchar(512) not null,
    created_at timestamptz not null,
    rollback_of varchar(36),
    constraint policy_publication_pkey primary key (id)
);

create table policy_publication_channel (
    id integer not null,
    publication_id varchar(36),
    constraint policy_publication_channel_id_check check ((id = 1)),
    constraint policy_publication_channel_pkey primary key (id)
);
-- The single row the publication service locks to serialise publications.
insert into policy_publication_channel (id, publication_id) values (1, null);

create table policy_opa_observation (
    instance_id varchar(255) not null,
    publication_id varchar(36) not null,
    digest varchar(71) not null,
    observed_at timestamptz not null,
    constraint policy_opa_observation_pkey primary key (instance_id)
);

create table policy_audit_outbox (
    id varchar(36) not null,
    aggregate_id varchar(36) not null,
    event_type varchar(32) not null,
    actor varchar(512) not null,
    occurred_at varchar(255) not null,
    details_json text not null,
    delivered_at varchar(255),
    dead_at varchar(255),
    attempts integer default 0 not null,
    next_attempt_at varchar(255),
    last_error varchar(2000),
    constraint policy_audit_outbox_pkey primary key (id)
);
create index policy_audit_due_idx on policy_audit_outbox (next_attempt_at) where ((delivered_at is null) and (dead_at is null));

-- Foreign keys

alter table capa add constraint capa_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
alter table capa add constraint capa_signal_id_fkey foreign key (signal_id) references monitoring_signal(id);
alter table change_assessment add constraint change_assessment_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
alter table compliance_control add constraint compliance_control_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
alter table compliance_snapshot add constraint compliance_snapshot_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
alter table fria_assessment add constraint fria_assessment_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
alter table governance_case add constraint governance_case_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
alter table governance_case_event add constraint governance_case_event_case_id_fkey foreign key (case_id) references governance_case(id);
alter table governance_case_evidence add constraint governance_case_evidence_case_id_fkey foreign key (case_id) references governance_case(id);
alter table human_override add constraint human_override_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
alter table human_oversight_assignment add constraint human_oversight_assignment_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
alter table impact_requirement add constraint impact_requirement_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
alter table monitoring_signal add constraint monitoring_signal_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
alter table policy_draft_event add constraint policy_draft_event_policy_id_fkey foreign key (policy_id) references policy_definition(id);
alter table policy_draft_event add constraint policy_draft_event_revision_id_fkey foreign key (revision_id) references policy_revision(id);
alter table policy_opa_observation add constraint policy_opa_observation_publication_id_fkey foreign key (publication_id) references policy_publication(id);
alter table policy_publication_channel add constraint policy_publication_channel_publication_id_fkey foreign key (publication_id) references policy_publication(id);
alter table policy_publication add constraint policy_publication_rollback_of_fkey foreign key (rollback_of) references policy_publication(id);
alter table policy_revision add constraint policy_revision_policy_id_fkey foreign key (policy_id) references policy_definition(id);
alter table system_event_sequence add constraint system_event_sequence_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
alter table transparency_notice add constraint transparency_notice_ai_system_id_fkey foreign key (ai_system_id) references ai_system(id);
