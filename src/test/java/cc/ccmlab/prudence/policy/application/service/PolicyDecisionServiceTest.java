package cc.ccmlab.prudence.policy.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ccmlab.prudence.evidence.application.port.out.EvidencePort;
import cc.ccmlab.prudence.policy.application.port.out.PolicyEnginePort;
import cc.ccmlab.prudence.policy.application.port.out.RegisteredSystemLookup;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import cc.ccmlab.prudence.registry.domain.RiskTier;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PolicyDecisionServiceTest {

  private static final DecisionContext CONTEXT =
      new DecisionContext("subject", "pipeline", "system-1", "invoke");

  @Test
  void deniesUnknownSystemAndRecordsDecision() {
    AtomicBoolean engineCalled = new AtomicBoolean();
    AtomicReference<PolicyDecision> recordedDecision = new AtomicReference<>();
    PolicyDecisionService service =
        service(
            aiSystemId -> Optional.empty(),
            ignoredContext -> {
              engineCalled.set(true);
              return true;
            },
            (context, decision) -> recordedDecision.set(decision));

    PolicyDecision decision = service.decide(CONTEXT);

    assertEquals(new PolicyDecision.Deny("unknown AI system: system-1"), decision);
    assertEquals(decision, recordedDecision.get());
    assertFalse(engineCalled.get());
  }

  @Test
  void requiresRiskOfficerApprovalForUnapprovedHighRiskSystem() {
    AtomicBoolean engineCalled = new AtomicBoolean();
    PolicyDecisionService service =
        service(
            aiSystemId ->
                Optional.of(
                    new RegisteredSystemLookup.SystemRiskView(aiSystemId, RiskTier.HIGH, false)),
            ignoredContext -> {
              engineCalled.set(true);
              return true;
            },
            (context, decision) -> {});

    PolicyDecision decision = service.decide(CONTEXT);

    assertEquals(
        new PolicyDecision.ApprovalRequired(
            "risk-officer", "high-risk system awaiting governance approval"),
        decision);
    assertFalse(engineCalled.get());
  }

  @Test
  void deniesUnapprovedProhibitedSystem() {
    PolicyDecisionService service =
        service(
            aiSystemId ->
                Optional.of(
                    new RegisteredSystemLookup.SystemRiskView(
                        aiSystemId, RiskTier.PROHIBITED, false)),
            ignoredContext -> true,
            (context, decision) -> {});

    PolicyDecision decision = service.decide(CONTEXT);

    assertEquals(new PolicyDecision.Deny("prohibited AI system"), decision);
  }

  @Test
  void allowsApprovedSystemWhenPolicyEngineAllows() {
    PolicyDecisionService service =
        service(approvedSystemLookup(), ignoredContext -> true, (context, decision) -> {});

    PolicyDecision decision = service.decide(CONTEXT);

    assertInstanceOf(PolicyDecision.Allow.class, decision);
  }

  @Test
  void deniesApprovedSystemWhenPolicyEngineDenies() {
    PolicyDecisionService service =
        service(approvedSystemLookup(), ignoredContext -> false, (context, decision) -> {});

    PolicyDecision decision = service.decide(CONTEXT);

    assertEquals(new PolicyDecision.Deny("denied by policy engine"), decision);
  }

  @Test
  void recordsPolicyEngineDecisionAsEvidence() {
    AtomicReference<PolicyDecision> recordedDecision = new AtomicReference<>();
    PolicyDecisionService service =
        service(
            approvedSystemLookup(),
            ignoredContext -> true,
            (context, decision) -> recordedDecision.set(decision));

    PolicyDecision decision = service.decide(CONTEXT);

    assertTrue(decision instanceof PolicyDecision.Allow);
    assertEquals(decision, recordedDecision.get());
  }

  /// The caller's flow (an AOS task) reaches the evidence of a registered system's decision:
  /// the context rebuilt with the registered version and identity keeps it.
  @Test
  void aRegisteredSystemsDecisionKeepsTheCallersCorrelationId() {
    AtomicReference<DecisionContext> recorded = new AtomicReference<>();
    PolicyDecisionService service =
        service(
            approvedSystemLookup(),
            ignoredContext -> true,
            (context, decision) -> recorded.set(context));
    DecisionContext context =
        new DecisionContext(
            "subject",
            "pipeline",
            "system-1",
            "run",
            "v1",
            "sha",
            "unknown",
            "unknown",
            "cause-1",
            "task-4f1c2a5e",
            null,
            null,
            null,
            "d-1");

    service.decide(context);

    assertEquals("task-4f1c2a5e", recorded.get().correlationId());
    assertEquals("cause-1", recorded.get().causationId());
  }

  private static RegisteredSystemLookup approvedSystemLookup() {
    return aiSystemId ->
        Optional.of(new RegisteredSystemLookup.SystemRiskView(aiSystemId, RiskTier.LIMITED, true));
  }

  private static PolicyDecisionService service(
      RegisteredSystemLookup registry, PolicyEnginePort engine, EvidencePort evidence) {
    return new PolicyDecisionService(registry, engine, evidence);
  }
}
