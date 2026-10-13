package com.prudence.evidence.adapter.out.ernest;

import com.prudence.evidence.adapter.out.persistence.EvidenceOutboxJpaEntity;
import com.prudence.evidence.adapter.out.persistence.EvidenceOutboxJpaRepository;
import com.prudence.evidence.delivery.Delivery;
import com.prudence.evidence.delivery.DeliveryTarget;
import com.prudence.policy.domain.DecisionContext;
import com.prudence.policy.domain.PolicyDecision;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/// PDP decisions in the evidence outbox → Ernest, under the outbox event id as `inferenceId`.
@Component
public class DecisionDeliveryTarget implements DeliveryTarget<EvidenceOutboxJpaEntity> {

    private final EvidenceOutboxJpaRepository outbox;
    private final ErnestEvidenceAdapter ernest;

    public DecisionDeliveryTarget(EvidenceOutboxJpaRepository outbox, ErnestEvidenceAdapter ernest) {
        this.outbox = outbox;
        this.ernest = ernest;
    }

    @Override public String name() { return "decisions"; }
    @Override public List<EvidenceOutboxJpaEntity> due(String now, int limit) { return outbox.due(now, Limit.of(limit)); }
    @Override public String id(EvidenceOutboxJpaEntity e) { return e.getId(); }
    @Override public Delivery delivery(EvidenceOutboxJpaEntity e) { return e.getDelivery(); }
    @Override public int claim(String id, String expected, String leaseUntil) { return outbox.claim(id, expected, leaseUntil); }
    @Override public Optional<EvidenceOutboxJpaEntity> find(String id) { return outbox.findById(id); }
    @Override public void save(EvidenceOutboxJpaEntity e) { outbox.save(e); }

    @Override
    public Consumer<EvidenceOutboxJpaEntity> send(EvidenceOutboxJpaEntity e) {
        ernest.publishDecision(e.getId(), new DecisionContext(e.getSubject(), e.getPipeline(), e.getAiSystemId(),
                e.getAction(), e.getPolicyVersion(), e.getPolicyHash(), e.getSystemVersion(), e.getModelHash()),
                decision(e));
        return record -> {}; // nothing beyond "delivered" to record
    }

    private static PolicyDecision decision(EvidenceOutboxJpaEntity e) {
        return switch (e.getVerdict()) {
            case "ALLOW" -> PolicyDecision.allow();
            case "APPROVAL_REQUIRED" -> PolicyDecision.approval("risk-officer", e.getReason());
            default -> PolicyDecision.deny(e.getReason());
        };
    }
}
