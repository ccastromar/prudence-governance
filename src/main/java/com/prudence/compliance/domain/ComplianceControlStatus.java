package com.prudence.compliance.domain;

/// Lifecycle of an evidence contract. FAILED and WAIVED are explicit governance decisions,
/// never inferred from the presence of a document.
public enum ComplianceControlStatus {
    NOT_STARTED, EVIDENCE_REQUESTED, SATISFIED, EXPIRED, FAILED, WAIVED
}
