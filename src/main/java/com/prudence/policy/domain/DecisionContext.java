package com.prudence.policy.domain;

/// What AOS asks Prudence at a gate: may this subject run this pipeline against this AI
/// system, for this action/context?
public record DecisionContext(
        String subject,
        String pipeline,
        String aiSystemId,
        String action,
        String policyVersion,
        String policyHash,
        String systemVersion,
        String modelHash) {
    public DecisionContext(String subject, String pipeline, String aiSystemId, String action) {
        this(subject, pipeline, aiSystemId, action, "unversioned", "unknown", "unknown", "unknown");
    }
}
