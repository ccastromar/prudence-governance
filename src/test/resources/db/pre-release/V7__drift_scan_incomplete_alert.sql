-- New alert kind: the drift check could not read all execution evidence (e.g. Ernest
-- throttling), so it must not pass for a clean check.
alter table alert drop constraint if exists alert_kind_check;
alter table alert add constraint alert_kind_check check (kind in ('REVIEW_DUE','APPROVAL_EXPIRED','DRIFT_VIOLATION',
    'EVIDENCE_DEAD_LETTERED','DELIVERY_CONFIGURATION','SERIOUS_INCIDENT','INCIDENT_REPORT_DUE','INCIDENT_REPORT_OVERDUE',
    'DRIFT_SCAN_INCOMPLETE'));
