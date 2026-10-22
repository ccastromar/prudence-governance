package cc.ccmlab.prudence.policy.adapter.in.web.dto;

/// Decision API v1. `approver` is set only for APPROVAL_REQUIRED; `reason` for
/// APPROVAL_REQUIRED/DENY. `decisionId` is the id the decision was recorded under as evidence
/// (cite it when reporting the execution it allowed); `policyVersion` is the policy bundle
/// that produced it.
public record DecisionResponse(
    String decision,
    String reason,
    String approver,
    String decisionId,
    String policyVersion,
    String policyDigest) {}
