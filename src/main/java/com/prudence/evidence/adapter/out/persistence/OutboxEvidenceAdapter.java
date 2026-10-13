package com.prudence.evidence.adapter.out.persistence;

import com.prudence.evidence.application.port.out.EvidencePort;
import com.prudence.policy.domain.DecisionContext;
import com.prudence.policy.domain.PolicyDecision;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OutboxEvidenceAdapter implements EvidencePort {
    private final EvidenceOutboxJpaRepository outbox;
    public OutboxEvidenceAdapter(EvidenceOutboxJpaRepository outbox) { this.outbox = outbox; }

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
        outbox.save(new EvidenceOutboxJpaEntity(UUID.randomUUID().toString(), context.subject(), context.pipeline(),
                context.aiSystemId(), context.action(), context.policyVersion(), context.policyHash(),
                context.systemVersion(), context.modelHash(), verdict, reason));
    }
}
