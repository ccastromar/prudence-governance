package com.prudence.policy.application.service;

import com.prudence.evidence.application.port.out.EvidencePort;
import com.prudence.policy.application.port.in.DecidePolicyUseCase;
import com.prudence.policy.application.port.out.PolicyEnginePort;
import com.prudence.policy.application.port.out.RegisteredSystemLookup;
import com.prudence.policy.domain.DecisionContext;
import com.prudence.policy.domain.PolicyDecision;
import org.springframework.stereotype.Service;

/// The PDP. Composes governance facts (is the system registered, approved, high-risk?)
/// with the external policy engine, returns a typed decision, and records it as evidence.
@Service
public class PolicyDecisionService implements DecidePolicyUseCase {

    private final RegisteredSystemLookup registry;
    private final PolicyEnginePort engine;
    private final EvidencePort evidence;

    public PolicyDecisionService(RegisteredSystemLookup registry, PolicyEnginePort engine, EvidencePort evidence) {
        this.registry = registry;
        this.engine = engine;
        this.evidence = evidence;
    }

    @Override
    public PolicyDecision decide(DecisionContext context) {
        PolicyDecision decision = evaluate(context);
        evidence.recordDecision(context, decision); // best-effort; sealed by Ernest
        return decision;
    }

    private PolicyDecision evaluate(DecisionContext context) {
        var view = registry.find(context.aiSystemId());
        if (view.isEmpty()) {
            return PolicyDecision.deny("unknown AI system: " + context.aiSystemId());
        }
        var system = view.get();
        if (!system.approved()) {
            return switch (system.riskTier()) {
                case HIGH -> PolicyDecision.approval("risk-officer", "high-risk system awaiting governance approval");
                case PROHIBITED -> PolicyDecision.deny("prohibited AI system");
                default -> PolicyDecision.deny("AI system not approved");
            };
        }
        return engine.isAllowed(context)
                ? PolicyDecision.allow()
                : PolicyDecision.deny("denied by policy engine");
    }
}
