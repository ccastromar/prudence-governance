package cc.ccmlab.prudence.policy.application.port.in;

import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;

/// Driving port: the Policy Decision Point AOS gates consult.
public interface DecidePolicyUseCase {
  /// `aiSystemId`: the AI system the decision was recorded under, which the PDP may have taken
  /// from the agent (`contract/identity/` section 5); null when the caller's own id stands.
  record Result(
      PolicyDecision decision, String policyVersion, String policyDigest, String aiSystemId) {
    public Result(PolicyDecision decision, String policyVersion, String policyDigest) {
      this(decision, policyVersion, policyDigest, null);
    }
  }

  PolicyDecision decide(DecisionContext context);

  default Result decideWithDetails(DecisionContext context) {
    return new Result(decide(context), context.policyVersion(), context.policyHash());
  }
}
