-- Evolve profile controls from a binary checklist into explicit evidence contracts.
alter table compliance_control drop constraint if exists compliance_control_status_check;
update compliance_control set status='NOT_STARTED' where status='PENDING';
alter table compliance_control add constraint compliance_control_status_check check (status in ('NOT_STARTED','EVIDENCE_REQUESTED','SATISFIED','EXPIRED','FAILED','WAIVED'));
alter table compliance_control add column accepts varchar(1000) not null default 'URL,SHA-256,version,owner';
alter table compliance_control add column expires_after_days integer;
alter table compliance_control add column expires_on_version_change boolean not null default false;
alter table compliance_control add column blocks_version_change boolean not null default false;
alter table compliance_control add column evidence_source varchar(255) not null default 'MANUAL_OR_GRC';
alter table compliance_control add column evidence_dataset_version varchar(255);
alter table compliance_control add column evidence_metrics varchar(4000);
alter table compliance_control add column evidence_approver varchar(255);
alter table compliance_control add column evidence_attestation varchar(4000);
alter table compliance_control add column ernest_receipt varchar(2000);
