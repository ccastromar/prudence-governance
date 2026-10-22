package cc.ccmlab.prudence.policy.adapter.in.web;

import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.policy.application.port.in.DecidePolicyUseCase;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import java.util.UUID;

/// How every PDP front door (Decision API v1, AuthZEN) asks for a decision: the id is assigned
/// here and the PDP records the decision under it in the same transaction, so the id returned
/// is always the id of the evidence, never one that was not stored.
final class RecordedDecisions {

  record Recorded(
      String decisionId, String policyVersion, String policyDigest, PolicyDecision decision) {}

  private RecordedDecisions() {}

  static Recorded decide(
      DecidePolicyUseCase pdp,
      IntegrationProperties properties,
      String subject,
      String pipeline,
      String aiSystemId,
      String action) {
    String decisionId = UUID.randomUUID().toString();
    String policyVersion = properties.opa().policyVersion();
    var result =
        pdp.decideWithDetails(
            new DecisionContext(
                subject,
                pipeline,
                aiSystemId,
                action,
                policyVersion,
                properties.opa().policyHash(),
                "unknown",
                "unknown",
                null,
                null,
                null,
                null,
                null,
                decisionId));
    return new Recorded(
        decisionId, result.policyVersion(), result.policyDigest(), result.decision());
  }
}
