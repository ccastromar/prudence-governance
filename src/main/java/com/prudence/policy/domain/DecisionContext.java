package com.prudence.policy.domain;

/// What AOS asks Prudence at a gate: may this subject run this pipeline against this AI
/// system, for this action/context?
public record DecisionContext(
        String subject,
        String pipeline,
        String aiSystemId,
        String action) {}
