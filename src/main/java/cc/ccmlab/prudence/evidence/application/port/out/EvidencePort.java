package cc.ccmlab.prudence.evidence.application.port.out;

import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;

/// Driven port to Ernest: governance decisions and attestations are recorded as
/// tamper-evident evidence (sealed by Ernest), closing the control loop.
public interface EvidencePort {
  void recordDecision(DecisionContext context, PolicyDecision decision);
}
