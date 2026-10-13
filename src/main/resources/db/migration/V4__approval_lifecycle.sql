-- Periodic review: renewal of a still-valid approval (four-eyes for high risk), the one-off
-- renewal notice, and the EXPIRED state for approvals that lapsed without a renewal.
alter table ai_system add column renewal_first_approver varchar(255);
alter table ai_system add column renewal_first_approved_at varchar(255);
alter table ai_system add column review_due_notified_at varchar(255);

-- V1 declared the status check inline, which PostgreSQL names <table>_<column>_check.
alter table ai_system drop constraint if exists ai_system_approval_status_check;
alter table ai_system add constraint ai_system_approval_status_check check (approval_status in (
    'DRAFT', 'UNDER_ASSESSMENT', 'EVIDENCE_PENDING', 'PENDING_APPROVAL', 'PENDING_SECOND_APPROVAL',
    'APPROVED', 'EXPIRED', 'REJECTED', 'BLOCKED', 'SUSPENDED', 'RETIRED'));

-- The sweep scans approved systems on every pass.
create index ai_system_status_idx on ai_system (approval_status);
