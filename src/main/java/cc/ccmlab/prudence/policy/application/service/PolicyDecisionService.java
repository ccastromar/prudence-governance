package cc.ccmlab.prudence.policy.application.service;

import cc.ccmlab.prudence.evidence.application.port.out.EvidencePort;
import cc.ccmlab.prudence.policy.application.port.in.DecidePolicyUseCase;
import cc.ccmlab.prudence.policy.application.port.out.PolicyEnginePort;
import cc.ccmlab.prudence.policy.application.port.out.RegisteredSystemLookup;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// The PDP. Composes governance facts (is the system registered, approved, high-risk?)
/// with the external policy engine, returns a typed decision, and records it as evidence.
@Service
public class PolicyDecisionService implements DecidePolicyUseCase {

  private final RegisteredSystemLookup registry;
  private final PolicyEnginePort engine;
  private final EvidencePort evidence;

  public PolicyDecisionService(
      RegisteredSystemLookup registry, PolicyEnginePort engine, EvidencePort evidence) {
    this.registry = registry;
    this.engine = engine;
    this.evidence = evidence;
  }

  @Override
  @Transactional
  public PolicyDecision decide(DecisionContext context) {
    var view = registry.find(context.aiSystemId());
    // Evidence carries the registered system version/model hash, so the decision can be
    // reproduced against the exact build it governed (not the caller's "unknown"), and the
    // system's name and risk tier as they stood at decision time (its identity in Ernest).
    DecisionContext effective =
        view.map(
                system ->
                    new DecisionContext(
                        context.subject(),
                        context.pipeline(),
                        context.aiSystemId(),
                        context.action(),
                        context.policyVersion(),
                        context.policyHash(),
                        system.systemVersion(),
                        system.modelHash(),
                        null,
                        null,
                        null,
                        system.name(),
                        system.riskTier().name(),
                        context.decisionId()))
            .orElse(context);
    PolicyDecision decision = evaluate(effective, view);
    evidence.recordDecision(effective, decision); // durable outbox, delivered to Ernest
    return decision;
  }

  private PolicyDecision evaluate(
      DecisionContext context, Optional<RegisteredSystemLookup.SystemRiskView> view) {
    if (view.isEmpty()) {
      return PolicyDecision.deny("unknown AI system: " + context.aiSystemId());
    }
    var system = view.get();
    if (!system.approved() && system.approvalExpired()) {
      // Not "awaiting approval": it had one and let it lapse — a renewal is what's needed.
      return switch (system.riskTier()) {
        case HIGH ->
            PolicyDecision.approval(
                "risk-officer", "high-risk system approval expired; renewal review required");
        default -> PolicyDecision.deny("AI system approval expired; renewal review required");
      };
    }
    if (!system.approved()) {
      return switch (system.riskTier()) {
        case HIGH ->
            PolicyDecision.approval(
                "risk-officer", "high-risk system awaiting governance approval");
        case PROHIBITED -> PolicyDecision.deny("prohibited AI system");
        default -> PolicyDecision.deny("AI system not approved");
      };
    }
    return engine.isAllowed(context)
        ? PolicyDecision.allow()
        : PolicyDecision.deny("denied by policy engine");
  }
}
