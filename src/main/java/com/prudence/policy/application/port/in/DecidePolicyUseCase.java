package com.prudence.policy.application.port.in;

import com.prudence.policy.domain.DecisionContext;
import com.prudence.policy.domain.PolicyDecision;

/// Driving port: the Policy Decision Point AOS gates consult.
public interface DecidePolicyUseCase {
  PolicyDecision decide(DecisionContext context);
}
