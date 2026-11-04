package cc.ccmlab.prudence.policy.application.service;

import cc.ccmlab.prudence.evidence.application.port.out.EvidencePort;
import cc.ccmlab.prudence.policy.application.port.in.DecidePolicyUseCase;
import cc.ccmlab.prudence.policy.application.port.out.PolicyEnginePort;
import cc.ccmlab.prudence.policy.application.port.out.RegisteredSystemLookup;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.DecisionIdentity;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import cc.ccmlab.prudence.registry.domain.RiskTier;
import java.util.List;
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
    return decideWithDetails(context).decision();
  }

  @Override
  @Transactional
  public Result decideWithDetails(DecisionContext requested) {
    // Who acts is settled before anything about the system (contract/identity/, section 7):
    // first whether the caller may speak for the agent, then which system the agent acts for.
    PolicyDecision decision = evaluateDelegation(requested);
    String aiSystemId = requested.aiSystemId();
    if (decision == null) {
      var resolved = resolveSystem(requested);
      decision = resolved.denial();
      if (resolved.aiSystemId() != null) aiSystemId = resolved.aiSystemId();
    }
    // A decision about no system still is evidence, under a marker that names no system.
    DecisionContext context =
        requested.withAiSystemId(aiSystemId == null ? UNRESOLVED_SYSTEM : aiSystemId);
    var view =
        aiSystemId == null
            ? Optional.<RegisteredSystemLookup.SystemRiskView>empty()
            : registry.find(aiSystemId);
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
                        // The caller's flow travels with the decision.
                        context.causationId(),
                        context.correlationId(),
                        null,
                        system.name(),
                        system.riskTier().name(),
                        context.decisionId(),
                        context.identity()))
            .orElse(context);
    if (decision == null) decision = evaluateHighRisk(effective, view);
    if (decision == null) decision = evaluateRegistry(effective, view);
    if (decision == null) {
      var evaluation = engine.evaluate(effective);
      effective = effective.withPolicy(evaluation.version(), evaluation.digest());
      decision =
          switch (evaluation.outcome()) {
            case ALLOW -> PolicyDecision.allow();
            // Only on a denial: was it a person's function the pipeline requires?
            case DENY ->
                engine.personFunctionMissing(effective)
                    ? PolicyDecision.deny(
                        "the pipeline needs a verified person with the required function",
                        "person_function_required")
                    : PolicyDecision.deny("denied by policy engine");
            case ERROR ->
                PolicyDecision.error("policy engine unavailable or publication is not active");
          };
    } else {
      effective = effective.withPolicy(null, null);
    }
    evidence.recordDecision(effective, decision); // durable outbox, delivered to Ernest
    return new Result(
        decision, effective.policyVersion(), effective.policyHash(), effective.aiSystemId());
  }

  /// A caller that names another agent must be allowed to speak for it. Never falls back to
  /// deciding about the caller.
  private PolicyDecision evaluateDelegation(DecisionContext context) {
    var identity = context.identity();
    // A forwarded token that failed, or a request that contradicts one: refused as it came.
    if (identity.rejection() != null)
      return PolicyDecision.deny(identity.rejection().reason(), identity.rejection().reasonCode());
    if (!identity.delegated()) return null;
    return switch (engine.delegation(identity.caller(), identity.agent())) {
      case ALLOWED -> null;
      case DENIED ->
          PolicyDecision.deny(
              "caller " + identity.caller() + " may not speak for agent " + identity.agent(),
              "agent_not_delegated");
      case ERROR -> PolicyDecision.error("policy engine unavailable or publication is not active");
    };
  }

  /// Recorded as the AI system of a decision whose agent acts for none or several systems.
  static final String UNRESOLVED_SYSTEM = "unresolved";

  private record ResolvedSystem(String aiSystemId, PolicyDecision denial) {}

  /// The AI system the agent acts for (`contract/identity/` section 5). A system sent must
  /// declare the agent: always for an agent named by another caller, and for a caller acting for
  /// itself once the system declares any agent. Without a system (an AuthZEN tool call), the one
  /// system the agent acts for.
  private ResolvedSystem resolveSystem(DecisionContext context) {
    var identity = context.identity();
    String agent = identity.agent();
    String sent = context.aiSystemId();
    if (sent == null || sent.isBlank()) {
      List<String> systems = registry.systemsOf(agent);
      if (systems.size() == 1) return new ResolvedSystem(systems.getFirst(), null);
      if (systems.isEmpty())
        return new ResolvedSystem(
            null,
            PolicyDecision.deny("agent " + agent + " acts for no AI system", "agent_unknown"));
      return new ResolvedSystem(
          null,
          PolicyDecision.deny(
              "agent " + agent + " acts for several AI systems; send the AI system",
              "agent_system_ambiguous"));
    }
    List<String> declared = registry.agentsOf(sent);
    boolean mustBeDeclared = identity.delegated() || !declared.isEmpty();
    if (mustBeDeclared && !declared.contains(agent))
      return new ResolvedSystem(
          sent,
          PolicyDecision.deny(
              "agent " + agent + " is not declared for AI system " + sent, "agent_not_in_system"));
    return new ResolvedSystem(sent, null);
  }

  /// A high-risk system is asked about by another caller only for an agent whose own token
  /// Prudence verified (`contract/identity/` section 6).
  private PolicyDecision evaluateHighRisk(
      DecisionContext context, Optional<RegisteredSystemLookup.SystemRiskView> view) {
    if (view.isEmpty() || view.get().riskTier() != RiskTier.HIGH) return null;
    if (context.identity().agentAssurance() != DecisionIdentity.AgentAssurance.DELEGATED)
      return null;
    return PolicyDecision.deny(
        "a high-risk system needs the agent's own token when another caller asks",
        "high_risk_requires_verified_agent");
  }

  private PolicyDecision evaluateRegistry(
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
    return null;
  }
}
