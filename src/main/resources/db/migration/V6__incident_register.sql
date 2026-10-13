-- AI incident register (AI Act Art. 73 / Art. 26(5)). The incident row holds the current
-- assessment and the filings; incident_event is its append-only timeline, each entry its own
-- evidence outbox for delivery to Ernest (same delivery columns as evidence_outbox).
create table incident (
    id varchar(255) not null,
    ai_system_id varchar(255),
    title varchar(500),
    description varchar(4000),
    occurred_at varchar(255),
    detected_at varchar(255),
    reported_by varchar(255),
    regulatory_role varchar(255) check (regulatory_role in ('PROVIDER','DEPLOYER','IMPORTER','DISTRIBUTOR')),
    member_states varchar(255),
    category varchar(255) check (category in ('DEATH','SERIOUS_HARM_TO_HEALTH','CRITICAL_INFRASTRUCTURE_DISRUPTION',
        'FUNDAMENTAL_RIGHTS_INFRINGEMENT','PROPERTY_OR_ENVIRONMENT_HARM','NOT_SERIOUS')),
    widespread_infringement boolean not null,
    causal_link varchar(255) check (causal_link in ('SUSPECTED','ESTABLISHED','RULED_OUT')),
    status varchar(255) check (status in ('OPEN','INITIAL_REPORT_SUBMITTED','FINAL_REPORT_SUBMITTED','CLOSED')),
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
    primary key (id)
);

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
    attempts integer not null default 0,
    next_attempt_at varchar(255),
    last_error varchar(2000),
    primary key (id)
);

create index incident_status_idx on incident (status);
create index incident_system_idx on incident (ai_system_id);
create index incident_event_incident_idx on incident_event (incident_id);
create index incident_event_pending_idx on incident_event (delivered_at, dead_at, next_attempt_at);

-- New alert kinds. V5 declared the check inline, which PostgreSQL names <table>_<column>_check.
alter table alert drop constraint if exists alert_kind_check;
alter table alert add constraint alert_kind_check check (kind in ('REVIEW_DUE','APPROVAL_EXPIRED','DRIFT_VIOLATION',
    'EVIDENCE_DEAD_LETTERED','DELIVERY_CONFIGURATION','SERIOUS_INCIDENT','INCIDENT_REPORT_DUE','INCIDENT_REPORT_OVERDUE'));
