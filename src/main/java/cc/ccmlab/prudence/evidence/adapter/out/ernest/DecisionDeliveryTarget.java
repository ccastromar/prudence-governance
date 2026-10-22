package cc.ccmlab.prudence.evidence.adapter.out.ernest;

import cc.ccmlab.prudence.evidence.adapter.out.persistence.EvidenceOutboxJpaEntity;
import cc.ccmlab.prudence.evidence.adapter.out.persistence.EvidenceOutboxJpaRepository;
import cc.ccmlab.prudence.evidence.delivery.DecisionPublisher;
import cc.ccmlab.prudence.evidence.delivery.Delivery;
import cc.ccmlab.prudence.evidence.delivery.DeliveryTarget;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/// PDP decisions in the evidence outbox → Ernest, under the outbox event id as `inferenceId`.
@Component
public class DecisionDeliveryTarget implements DeliveryTarget<EvidenceOutboxJpaEntity> {

  private final EvidenceOutboxJpaRepository outbox;
  private final DecisionPublisher ernest;
  private final int batchSize;

  public DecisionDeliveryTarget(
      EvidenceOutboxJpaRepository outbox,
      DecisionPublisher ernest,
      @Value("${prudence.ernest.batch-size:200}") int batchSize) {
    this.outbox = outbox;
    this.ernest = ernest;
    this.batchSize = Math.max(1, Math.min(batchSize, 500)); // Ernest accepts up to 500
  }

  @Override
  public String name() {
    return "decisions";
  }

  @Override
  public List<EvidenceOutboxJpaEntity> due(String now, int limit) {
    return outbox.due(now, Limit.of(limit));
  }

  @Override
  public String id(EvidenceOutboxJpaEntity e) {
    return e.getId();
  }

  @Override
  public Delivery delivery(EvidenceOutboxJpaEntity e) {
    return e.getDelivery();
  }

  @Override
  public int claim(String id, String expected, String leaseUntil) {
    return outbox.claim(id, expected, leaseUntil);
  }

  @Override
  public Optional<EvidenceOutboxJpaEntity> find(String id) {
    return outbox.findById(id);
  }

  @Override
  public List<EvidenceOutboxJpaEntity> deadLettered(int limit) {
    return outbox.deadLettered(Limit.of(limit));
  }

  @Override
  public String aiSystemId(EvidenceOutboxJpaEntity e) {
    return e.getAiSystemId();
  }

  @Override
  public void save(EvidenceOutboxJpaEntity e) {
    outbox.save(e);
  }

  /// Decisions are the high-volume stream: batched when Ernest supports it (see
  /// DecisionPublisher#publishDecisions), one at a time otherwise.
  @Override
  public int batchSize() {
    return ernest.batchCapable() ? batchSize : 1;
  }

  @Override
  public List<Outcome<EvidenceOutboxJpaEntity>> sendBatch(List<EvidenceOutboxJpaEntity> records) {
    List<DecisionPublisher.Item> items =
        records.stream()
            .map(e -> new DecisionPublisher.Item(e.getId(), context(e), decision(e)))
            .toList();
    List<Exception> failures = ernest.publishDecisions(items);
    return failures.stream()
        .map(
            f ->
                f == null
                    ? Outcome.<EvidenceOutboxJpaEntity>delivered(record -> {})
                    : Outcome.<EvidenceOutboxJpaEntity>failed(f))
        .toList();
  }

  private static DecisionContext context(EvidenceOutboxJpaEntity e) {
    return new DecisionContext(
        e.getSubject(),
        e.getPipeline(),
        e.getAiSystemId(),
        e.getAction(),
        e.getPolicyVersion(),
        e.getPolicyHash(),
        e.getSystemVersion(),
        e.getModelHash(),
        e.getCausationId(),
        e.getCorrelationId(),
        e.getSystemSequence(),
        e.getAiSystemName(),
        e.getRiskTier(),
        e.getId());
  }

  @Override
  public Consumer<EvidenceOutboxJpaEntity> send(EvidenceOutboxJpaEntity e) throws Exception {
    ernest.publishDecision(e.getId(), context(e), decision(e));
    return record -> {}; // nothing beyond "delivered" to record
  }

  private static PolicyDecision decision(EvidenceOutboxJpaEntity e) {
    return switch (e.getVerdict()) {
      case "ALLOW" -> PolicyDecision.allow();
      case "APPROVAL_REQUIRED" -> PolicyDecision.approval("risk-officer", e.getReason());
      case "ERROR" -> PolicyDecision.error(e.getReason());
      default -> PolicyDecision.deny(e.getReason());
    };
  }
}
