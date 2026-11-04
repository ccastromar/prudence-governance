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

  /// Whether the policy lets `caller` speak for `agent` (`data.prudence.speaks_for`).
  enum Delegation {
    ALLOWED,
    DENIED,
    ERROR
  }

  boolean isAllowed(DecisionContext context);

  /// Fail closed: an engine that does not know delegation lets no caller speak for another.
  default Delegation delegation(String caller, String agent) {
    return Delegation.DENIED;
  }

  /// After a denial: whether it was because the pipeline requires a verified person with a
  /// function this decision's person lacks (`data.prudence.person_requires`). Only names the
  /// denial; it never turns one into an ALLOW.
  default boolean personFunctionMissing(DecisionContext context) {
    return false;
  }

  default Evaluation evaluate(DecisionContext context) {
    return new Evaluation(
        isAllowed(context) ? Outcome.ALLOW : Outcome.DENY,
        context.policyVersion(),
        context.policyHash());
  }
}
