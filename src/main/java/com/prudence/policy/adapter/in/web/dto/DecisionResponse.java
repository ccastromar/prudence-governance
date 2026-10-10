package com.prudence.policy.adapter.in.web.dto;

/// `approver` is set only for APPROVAL_REQUIRED; `reason` for APPROVAL_REQUIRED/DENY.
public record DecisionResponse(String decision, String reason, String approver) {}
