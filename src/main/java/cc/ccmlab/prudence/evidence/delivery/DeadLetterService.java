package cc.ccmlab.prudence.evidence.delivery;

import cc.ccmlab.prudence.shared.exception.DomainException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/// Operator view of records the delivery worker gave up on (dead-lettered), across every
/// target, and the way back: once the cause is fixed (Ernest reachable again, credentials or
/// payload corrected), a record is requeued with a fresh attempt budget and delivered at once.
@Service
public class DeadLetterService {

  private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

  public record DeadLetter(
      String target, String id, String aiSystemId, int attempts, String deadAt, String lastError) {}

  private final List<DeliveryTarget<?>> targets;
  private final DeliverEvidence delivery;

  public DeadLetterService(List<DeliveryTarget<?>> targets, DeliverEvidence delivery) {
    this.targets = targets;
    this.delivery = delivery;
  }

  @Transactional(readOnly = true)
  public List<DeadLetter> list(int limitPerTarget) {
    int limit = Math.max(1, Math.min(limitPerTarget, 500));
    List<DeadLetter> all = new ArrayList<>();
    for (DeliveryTarget<?> target : targets) collect(target, limit, all);
    all.sort(Comparator.comparing(DeadLetter::deadAt).reversed());
    return all;
  }

  /// Requeue one dead-lettered record and deliver it right after commit.
  @Transactional
  public void retry(String targetName, String id, String actor) {
    DeliveryTarget<?> target =
        targets.stream()
            .filter(t -> t.name().equals(targetName))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("unknown delivery target: " + targetName));
    requeue(target, id);
    log.info("dead-lettered {} record {} requeued", targetName, id);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            delivery.deliverNow(targetName);
          }
        });
  }

  private static <E> void collect(DeliveryTarget<E> target, int limit, List<DeadLetter> into) {
    for (E record : target.deadLettered(limit)) {
      Delivery d = target.delivery(record);
      into.add(
          new DeadLetter(
              target.name(),
              target.id(record),
              target.aiSystemId(record),
              d.getAttempts(),
              d.getDeadAt(),
              d.getLastError()));
    }
  }

  private static <E> void requeue(DeliveryTarget<E> target, String id) {
    E record =
        target
            .find(id)
            .orElseThrow(
                () -> new IllegalArgumentException("no " + target.name() + " record " + id));
    try {
      target.delivery(record).requeue();
    } catch (IllegalStateException e) {
      throw new DomainException(target.name() + " record " + id + " is not dead-lettered");
    }
    target.save(record);
  }
}
