package cc.ccmlab.prudence.policy.application.port.out;

import cc.ccmlab.prudence.policy.domain.DecisionContext;

/// Driven port to the external policy engine (OPA/Cedar). Fail-closed: an unreachable
/// engine must not grant access.
public interface PolicyEnginePort {
  boolean isAllowed(DecisionContext context);
}
