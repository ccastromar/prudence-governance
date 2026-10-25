package cc.ccmlab.prudence.evidence.adapter.out.persistence;

import cc.ccmlab.prudence.causality.CausalityService;
import cc.ccmlab.prudence.evidence.application.port.out.EvidencePort;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutboxEvidenceAdapter implements EvidencePort {
  private final EvidenceOutboxJpaRepository outbox;
  private final CausalityService causality;

  public OutboxEvidenceAdapter(EvidenceOutboxJpaRepository outbox, CausalityService causality) {
    this.outbox = outbox;
    this.causality = causality;
  }

  @Override
  public void recordDecision(DecisionContext context, PolicyDecision decision) {
    String verdict =
        switch (decision) {
          case PolicyDecision.Allow ignored -> "ALLOW";
          case PolicyDecision.Deny ignored -> "DENY";
          case PolicyDecision.Error ignored -> "ERROR";
          case PolicyDecision.ApprovalRequired ignored -> "APPROVAL_REQUIRED";
        };
    String reason =
        switch (decision) {
          case PolicyDecision.Allow ignored -> null;
          case PolicyDecision.Deny d -> d.reason();
          case PolicyDecision.Error e -> e.reason();
          case PolicyDecision.ApprovalRequired a -> a.reason();
        };
    var record =
        new EvidenceOutboxJpaEntity(
            // The id the PDP already returned to the caller (Decision API v1), so a runtime
            // can cite the exact recorded decision; a fresh one when none was assigned.
            context.decisionId() != null ? context.decisionId() : UUID.randomUUID().toString(),
            context.subject(),
            context.pipeline(),
            context.aiSystemId(),
            context.action(),
            context.policyVersion(),
            context.policyHash(),
            context.systemVersion(),
            context.modelHash(),
            verdict,
            reason);
    record.setSystemIdentity(context.aiSystemName(), context.riskTier());
    if (context.riskTier() == null) {
      // The PDP stamps the registered risk tier, so none means the registry does not know this
      // system. Its fail-closed DENY is still evidence, but an unregistered id has no event
      // sequence (system_event_sequence references ai_system): record it unsequenced rather
      // than fail the decision or mint sequence rows from a caller-supplied id.
      record.setCausality(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null);
    } else {
      var envelope =
          causality.envelope(
              "GOVERNANCE_DECISION",
              context.aiSystemId(),
              context.systemVersion(),
              record.getId(),
              null,
              null,
              java.util.Map.of("verdict", verdict));
      record.setCausality(envelope.eventId(), envelope.correlationId(), envelope.systemSequence());
    }
    outbox.save(record);
  }
}
