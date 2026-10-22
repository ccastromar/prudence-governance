package cc.ccmlab.prudence.policy.application.port.in;

import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;

/// Driving port: the Policy Decision Point AOS gates consult.
public interface DecidePolicyUseCase {
  record Result(PolicyDecision decision, String policyVersion, String policyDigest) {}

  PolicyDecision decide(DecisionContext context);

  default Result decideWithDetails(DecisionContext context) {
    return new Result(decide(context), context.policyVersion(), context.policyHash());
  }
}
