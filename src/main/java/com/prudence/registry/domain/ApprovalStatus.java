package com.prudence.registry.domain;

public enum ApprovalStatus {
    DRAFT,
    UNDER_ASSESSMENT,
    EVIDENCE_PENDING,
    PENDING_APPROVAL,
    PENDING_SECOND_APPROVAL,
    APPROVED,
    /// The approval's validity lapsed without a renewal; it must be resubmitted and reviewed.
    EXPIRED,
    REJECTED,
    BLOCKED,
    SUSPENDED,
    RETIRED
}
