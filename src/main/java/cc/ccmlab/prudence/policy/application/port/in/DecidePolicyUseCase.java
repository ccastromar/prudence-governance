package cc.ccmlab.prudence.policy.application.port.in;

import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;

/// Driving port: the Policy Decision Point AOS gates consult.
public interface DecidePolicyUseCase {
  PolicyDecision decide(DecisionContext context);
}
