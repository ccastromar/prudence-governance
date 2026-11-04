package cc.ccmlab.prudence.policy.adapter.in.web.dto;

import java.util.Map;

/// Decision API v1. `approver` is set only for APPROVAL_REQUIRED; `reason` for
/// APPROVAL_REQUIRED/DENY. `decisionId` is the id the decision was recorded under as evidence
/// (cite it when reporting the execution it allowed); `policyVersion` is the policy bundle
/// that produced it. `reasonCode` is a stable code for some denials and `identity` who asked,
/// who acts and for whom (`contract/identity/`).
public record DecisionResponse(
    String decision,
    String reason,
    String approver,
    String decisionId,
    String policyVersion,
    String policyDigest,
    String reasonCode,
    Map<String, Object> identity) {

  public DecisionResponse(
      String decision,
      String reason,
      String approver,
      String decisionId,
      String policyVersion,
      String policyDigest) {
    this(decision, reason, approver, decisionId, policyVersion, policyDigest, null, null);
  }
}
