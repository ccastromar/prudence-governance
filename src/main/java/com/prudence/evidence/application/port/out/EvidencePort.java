package com.prudence.evidence.application.port.out;

import com.prudence.policy.domain.DecisionContext;
import com.prudence.policy.domain.PolicyDecision;

/// Driven port to Ernest: governance decisions and attestations are recorded as
/// tamper-evident evidence (sealed by Ernest), closing the control loop.
public interface EvidencePort {
    void recordDecision(DecisionContext context, PolicyDecision decision);
}
