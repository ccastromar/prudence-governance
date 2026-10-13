package com.prudence.evidence.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/// Delivers every kind of pending outbound record: governance evidence to Ernest, alerts by
/// email. Each record is leased,
/// delivered and marked in its own short transactions — no database transaction is held open
/// across the network call, and the lease keeps concurrent instances from delivering the same
/// record. Delivery is at-least-once under a stable id, so a re-send is identifiable.
///
/// Failures follow the [DeliveryFailure] contract, classified by each target (see
/// [Delivery#recordFailure]): transient ones back off and dead-letter after `max-attempts`; a
/// permanent rejection dead-letters at once; a configuration failure waits without consuming
/// attempts and is reported once per target, not per record. Dead-letters and configuration
/// failures are handed to [DeliveryListener]s (which raise alerts).
@Component
public class EvidenceDeliveryWorker implements DeliverEvidence {
    private static final Logger log = LoggerFactory.getLogger(EvidenceDeliveryWorker.class);
    /// Must exceed the worst-case Ernest round-trip (see ErnestHttp timeouts).
    private static final Duration LEASE = Duration.ofMinutes(5);
    /// Retry cadence while Ernest refuses Prudence's credentials or is not configured.
    private static final Duration CONFIGURATION_RETRY = Duration.ofMinutes(5);
    private static final int BATCH = 50;

    private final List<DeliveryTarget<?>> targets;
    private final List<DeliveryListener> listeners;
    private final TransactionTemplate tx;
    private final int maxAttempts;
    /// Targets whose configuration is currently failing, so the operator gets one ERROR (and
    /// one alert) when it starts and one INFO when it recovers, not one per record.
    private final Set<String> configurationFailing = ConcurrentHashMap.newKeySet();

    public EvidenceDeliveryWorker(List<DeliveryTarget<?>> targets, List<DeliveryListener> listeners,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${prudence.evidence.max-attempts:20}") int maxAttempts) {
        this.targets = targets;
        this.listeners = listeners;
        this.tx = new TransactionTemplate(transactionManager);
        // Always a fresh transaction: deliverNow may run in an after-commit callback, where the
        // finished transaction's resources are still bound to the thread.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${prudence.evidence.outbox-delay-ms:30000}")
    public void deliverPending() {
        targets.forEach(this::runSafely);
    }

    @Override
    public void deliverNow(String target) {
        targets.stream().filter(t -> t.name().equals(target)).forEach(this::runSafely);
    }

    private void runSafely(DeliveryTarget<?> target) {
        try {
            run(target);
        } catch (Exception e) {
            // A database problem in one target must not stop the others, nor fail a caller.
            log.warn("evidence delivery pass for {} failed: {}", target.name(), e.getMessage());
        }
    }

    private <E> void run(DeliveryTarget<E> target) {
        String now = Delivery.stamp(Instant.now());
        for (E due : target.due(now, BATCH)) {
            String id = target.id(due);
            if (!claim(target, id, target.delivery(due).getNextAttemptAt())) continue; // another worker holds it
            Consumer<E> receipt;
            try {
                receipt = target.send(due);
            } catch (Exception e) {
                handleFailure(target, id, e);
                continue;
            }
            update(target, id, record -> {
                receipt.accept(record);
                target.delivery(record).delivered();
            });
            if (configurationFailing.remove(target.name())) {
                log.info("{} delivery recovered", target.name());
            }
        }
    }

    private <E> boolean claim(DeliveryTarget<E> target, String id, String expected) {
        String leaseUntil = Delivery.stamp(Instant.now().plus(LEASE));
        Integer claimed = tx.execute(s -> target.claim(id, expected, leaseUntil));
        return claimed != null && claimed == 1;
    }

    private <E> void handleFailure(DeliveryTarget<E> target, String id, Exception e) {
        DeliveryFailure failure = target.classify(e);
        Delivery.Status[] outcome = new Delivery.Status[1];
        update(target, id, record -> {
            outcome[0] = target.delivery(record).recordFailure(failure, e, maxAttempts, CONFIGURATION_RETRY);
            // Same transaction as the dead-letter, so the alert cannot be lost or duplicated.
            if (outcome[0] == Delivery.Status.DEAD && target.reportsFailures()) {
                listeners.forEach(l -> l.deadLettered(target.name(), id, failure, e.getMessage()));
            }
        });
        if (failure == DeliveryFailure.CONFIGURATION) {
            if (configurationFailing.add(target.name())) {
                log.error("{} delivery refuses Prudence's configuration (credentials/endpoint); records are "
                        + "queued until fixed: {}", target.name(), e.getMessage());
                if (target.reportsFailures()) {
                    tx.executeWithoutResult(s -> listeners.forEach(l -> l.configurationFailing(target.name(), e.getMessage())));
                }
            }
        } else if (outcome[0] == Delivery.Status.DEAD) {
            log.error("{} record {} dead-lettered ({}): {}", target.name(), id,
                    failure == DeliveryFailure.PERMANENT ? "rejected" : "out of attempts", e.getMessage());
        } else {
            log.warn("{} delivery failed for {} (will retry): {}", target.name(), id, e.getMessage());
        }
    }

    private <E> void update(DeliveryTarget<E> target, String id, Consumer<E> change) {
        tx.executeWithoutResult(s -> target.find(id).ifPresent(record -> {
            change.accept(record);
            target.save(record);
        }));
    }
}
