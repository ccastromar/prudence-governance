package com.prudence.evidence.adapter.out.persistence;

import com.prudence.evidence.application.port.out.EvidencePort;
import com.prudence.policy.domain.DecisionContext;
import com.prudence.policy.domain.PolicyDecision;
import com.prudence.causality.CausalityService;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OutboxEvidenceAdapter implements EvidencePort {
    private final EvidenceOutboxJpaRepository outbox;
    private final CausalityService causality;
    public OutboxEvidenceAdapter(EvidenceOutboxJpaRepository outbox, CausalityService causality) { this.outbox = outbox; this.causality = causality; }

    @Override
    public void recordDecision(DecisionContext context, PolicyDecision decision) {
        String verdict = switch (decision) {
            case PolicyDecision.Allow ignored -> "ALLOW";
            case PolicyDecision.Deny ignored -> "DENY";
            case PolicyDecision.ApprovalRequired ignored -> "APPROVAL_REQUIRED";
        };
        String reason = switch (decision) {
            case PolicyDecision.Allow ignored -> null;
            case PolicyDecision.Deny d -> d.reason();
            case PolicyDecision.ApprovalRequired a -> a.reason();
        };
        var record = new EvidenceOutboxJpaEntity(UUID.randomUUID().toString(), context.subject(), context.pipeline(),
                context.aiSystemId(), context.action(), context.policyVersion(), context.policyHash(),
                context.systemVersion(), context.modelHash(), verdict, reason);
        var envelope = causality.envelope("GOVERNANCE_DECISION", context.aiSystemId(), context.systemVersion(), record.getId(),
                null, null, java.util.Map.of("verdict", verdict));
        record.setCausality(envelope.eventId(), envelope.correlationId(), envelope.systemSequence());
        outbox.save(record);
    }
}
