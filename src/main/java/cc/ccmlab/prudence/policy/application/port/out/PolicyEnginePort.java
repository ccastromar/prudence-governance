package cc.ccmlab.prudence.policy.application.port.out;

import cc.ccmlab.prudence.policy.domain.DecisionContext;

/// Driven port to the external policy engine (OPA/Cedar). Fail-closed: an unreachable
/// engine must not grant access.
public interface PolicyEnginePort {
  enum Outcome {
    ALLOW,
    DENY,
    ERROR
  }

  record Evaluation(Outcome outcome, String version, String digest) {}

  boolean isAllowed(DecisionContext context);

  default Evaluation evaluate(DecisionContext context) {
    return new Evaluation(
        isAllowed(context) ? Outcome.ALLOW : Outcome.DENY,
        context.policyVersion(),
        context.policyHash());
  }
}
