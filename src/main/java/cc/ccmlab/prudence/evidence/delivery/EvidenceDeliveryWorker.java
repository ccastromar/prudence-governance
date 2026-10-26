package cc.ccmlab.prudence.evidence.delivery;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
  /// Records fetched per pass; a run keeps passing while passes come back full (drain), up
  /// to MAX_PASSES so one busy target cannot starve the others for long.
  private static final int BATCH = 500;
  private static final int MAX_PASSES = 40;

  private final List<DeliveryTarget<?>> targets;
  private final List<DeliveryListener> listeners;
  private final TransactionTemplate tx;
  private final int maxAttempts;
  private final DeliveryMetrics metrics;
  private final Tracer tracer;
  /// Sends run on this pool: chunks (or single records) of one pass go out concurrently.
  private final ExecutorService senders;
  /// Targets whose configuration is currently failing, so the operator gets one ERROR (and
  /// one alert) when it starts and one INFO when it recovers, not one per record.
  private final Set<String> configurationFailing = ConcurrentHashMap.newKeySet();

  EvidenceDeliveryWorker(
      List<DeliveryTarget<?>> targets,
      List<DeliveryListener> listeners,
      PlatformTransactionManager transactionManager,
      int maxAttempts,
      int concurrency) {
    this(
        targets,
        listeners,
        transactionManager,
        maxAttempts,
        concurrency,
        new SimpleMeterRegistry(),
        OpenTelemetry.noop());
  }

  @Autowired
  public EvidenceDeliveryWorker(
      List<DeliveryTarget<?>> targets,
      List<DeliveryListener> listeners,
      PlatformTransactionManager transactionManager,
      @Value("${prudence.evidence.max-attempts:20}") int maxAttempts,
      @Value("${prudence.evidence.delivery-concurrency:8}") int concurrency,
      MeterRegistry meters,
      ObjectProvider<OpenTelemetry> openTelemetry) {
    this(
        targets,
        listeners,
        transactionManager,
        maxAttempts,
        concurrency,
        meters,
        openTelemetry.getIfAvailable(OpenTelemetry::noop));
  }

  EvidenceDeliveryWorker(
      List<DeliveryTarget<?>> targets,
      List<DeliveryListener> listeners,
      PlatformTransactionManager transactionManager,
      int maxAttempts,
      int concurrency,
      MeterRegistry meters,
      OpenTelemetry openTelemetry) {
    this.metrics = new DeliveryMetrics(meters);
    this.tracer = openTelemetry.getTracer("cc.ccmlab.prudence.evidence.delivery");
    this.targets = targets;
    this.listeners = listeners;
    this.tx = new TransactionTemplate(transactionManager);
    // Always a fresh transaction: deliverNow may run in an after-commit callback, where the
    // finished transaction's resources are still bound to the thread.
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.maxAttempts = maxAttempts;
    AtomicInteger n = new AtomicInteger();
    this.senders =
        Executors.newFixedThreadPool(
            Math.max(1, concurrency),
            r -> {
              Thread t = new Thread(r, "evidence-sender-" + n.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
  }

  @PreDestroy
  void shutdown() {
    senders.shutdown();
  }

  /// One scheduled run keeps going, round-robin over the targets, while any of them still has
  /// a backlog: the outbox delay is the idle poll interval, not a pause inside a drain. Each
  /// round gives every target at most MAX_PASSES, so one busy target cannot starve the rest.
  @Scheduled(fixedDelayString = "${prudence.evidence.outbox-delay-ms:2000}")
  public void deliverPending() {
    boolean backlog;
    do {
      backlog = false;
      for (DeliveryTarget<?> target : targets) {
        backlog |= runSafely(target);
      }
    } while (backlog && !Thread.currentThread().isInterrupted());
  }

  @Override
  public void deliverNow(String target) {
    targets.stream().filter(t -> t.name().equals(target)).forEach(this::runSafely);
  }

  /// Returns true when the target stopped at MAX_PASSES with records still due.
  private boolean runSafely(DeliveryTarget<?> target) {
    try {
      return run(target);
    } catch (Exception e) {
      // A database problem in one target must not stop the others, nor fail a caller.
      log.warn("evidence delivery pass for {} failed: {}", target.name(), e.getMessage());
      return false;
    }
  }

  private <E> boolean run(DeliveryTarget<E> target) {
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      List<E> due = target.due(Delivery.stamp(Instant.now()), BATCH);
      metrics.due(target.name(), due.size());
      if (due.isEmpty()) return false;
      List<E> claimed = claimAll(target, due);
      deliver(target, claimed);
      // Drained, or every due record was taken by another instance: nothing more for us.
      if (due.size() < BATCH || claimed.isEmpty()) return false;
    }
    return true;
  }

  /// Splits the claimed records into the target's batch size and sends the chunks
  /// concurrently; each record is then marked delivered or failed on its own.
  private <E> void deliver(DeliveryTarget<E> target, List<E> claimed) {
    int size = Math.max(1, target.batchSize());
    List<Callable<Void>> chunks = new ArrayList<>();
    for (int i = 0; i < claimed.size(); i += size) {
      List<E> chunk = claimed.subList(i, Math.min(i + size, claimed.size()));
      chunks.add(
          () -> {
            sendChunk(target, chunk);
            return null;
          });
    }
    try {
      for (Future<Void> f : senders.invokeAll(chunks)) {
        try {
          f.get();
        } catch (ExecutionException e) {
          log.warn("{} delivery chunk failed: {}", target.name(), e.getCause().getMessage());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /// Each send is a new span (a new trace: it runs after the work that wrote the records has
  /// ended), linked to the trace context stored with each record, so a decision's trace leads
  /// to its delivery and every retry of it (contract/observability, section 4).
  private <E> void sendChunk(DeliveryTarget<E> target, List<E> chunk) {
    SpanBuilder builder =
        tracer
            .spanBuilder("prudence.evidence.deliver")
            .setNoParent()
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute("prudence.delivery.target", target.name())
            .setAttribute("messaging.batch.message_count", (long) chunk.size());
    for (E record : chunk) {
      SpanContext stored = spanContext(target.delivery(record).getTraceparent());
      if (stored.isValid()) builder.addLink(stored);
    }
    Span span = builder.startSpan();
    try (Scope ignored = span.makeCurrent()) {
      send(target, chunk, span);
    } finally {
      span.end();
    }
  }

  /// The span context a stored traceparent names, or an invalid one.
  static SpanContext spanContext(String traceparent) {
    if (traceparent == null) return SpanContext.getInvalid();
    String[] parts = traceparent.split("-");
    if (parts.length != 4 || !"00".equals(parts[0]) || parts[3].length() != 2) {
      return SpanContext.getInvalid();
    }
    try {
      return SpanContext.createFromRemoteParent(
          parts[1], parts[2], TraceFlags.fromHex(parts[3], 0), TraceState.getDefault());
    } catch (RuntimeException e) {
      return SpanContext.getInvalid();
    }
  }

  private <E> void send(DeliveryTarget<E> target, List<E> chunk, Span span) {
    List<DeliveryTarget.Outcome<E>> outcomes;
    long started = System.nanoTime();
    try {
      outcomes = target.sendBatch(chunk);
      metrics.send(target.name(), true, System.nanoTime() - started);
    } catch (Exception e) {
      metrics.send(target.name(), false, System.nanoTime() - started);
      span.setStatus(StatusCode.ERROR, "send failed");
      chunk.forEach(record -> handleFailure(target, target.id(record), e));
      return;
    }
    Map<String, Consumer<E>> delivered = new LinkedHashMap<>();
    for (int i = 0; i < chunk.size(); i++) {
      String id = target.id(chunk.get(i));
      DeliveryTarget.Outcome<E> outcome =
          i < outcomes.size()
              ? outcomes.get(i)
              : DeliveryTarget.Outcome.failed(
                  new IllegalStateException("no outcome reported for " + id));
      if (outcome.failure() != null) {
        handleFailure(target, id, outcome.failure());
        continue;
      }
      delivered.put(id, outcome.receipt());
    }
    if (delivered.isEmpty()) return;
    markDelivered(target, delivered);
    metrics.records(target.name(), "delivered", delivered.size());
    if (configurationFailing.remove(target.name())) {
      log.info("{} delivery recovered", target.name());
    }
  }

  /// Marks a chunk's delivered records in one transaction. Should that transaction fail, the
  /// records are marked one by one, so one bad row cannot hold the rest back (a record left
  /// unmarked is re-sent after its lease and deduplicated by its stable id).
  private <E> void markDelivered(DeliveryTarget<E> target, Map<String, Consumer<E>> delivered) {
    try {
      tx.executeWithoutResult(
          s ->
              delivered.forEach(
                  (id, receipt) ->
                      target
                          .find(id)
                          .ifPresent(
                              record -> {
                                receipt.accept(record);
                                target.delivery(record).delivered();
                                target.save(record);
                              })));
    } catch (Exception e) {
      log.warn(
          "{}: marking {} records delivered together failed, marking one by one: {}",
          target.name(),
          delivered.size(),
          e.getMessage());
      delivered.forEach(
          (id, receipt) ->
              update(
                  target,
                  id,
                  record -> {
                    receipt.accept(record);
                    target.delivery(record).delivered();
                  }));
    }
  }

  /// Leases a pass's records in one transaction: each row is still claimed on its own
  /// compare-and-set (only a worker that saw the same `nextAttemptAt` wins it), but the pass
  /// pays one commit instead of one per record.
  private <E> List<E> claimAll(DeliveryTarget<E> target, List<E> due) {
    String leaseUntil = Delivery.stamp(Instant.now().plus(LEASE));
    List<E> claimed =
        tx.execute(
            s -> {
              List<E> won = new ArrayList<>(due.size());
              for (E record : due) {
                if (target.claim(
                        target.id(record), target.delivery(record).getNextAttemptAt(), leaseUntil)
                    == 1) won.add(record);
              }
              return won;
            });
    return claimed == null ? List.of() : claimed;
  }

  private <E> void handleFailure(DeliveryTarget<E> target, String id, Exception e) {
    DeliveryFailure failure = target.classify(e);
    Delivery.Status[] outcome = new Delivery.Status[1];
    update(
        target,
        id,
        record -> {
          outcome[0] =
              target.delivery(record).recordFailure(failure, e, maxAttempts, CONFIGURATION_RETRY);
          // Same transaction as the dead-letter, so the alert cannot be lost or duplicated.
          if (outcome[0] == Delivery.Status.DEAD && target.reportsFailures()) {
            listeners.forEach(l -> l.deadLettered(target.name(), id, failure, e.getMessage()));
          }
        });
    metrics.records(
        target.name(),
        failure == DeliveryFailure.CONFIGURATION
            ? "configuration"
            : outcome[0] == Delivery.Status.DEAD ? "dead" : "retry",
        1);
    if (failure == DeliveryFailure.CONFIGURATION) {
      if (configurationFailing.add(target.name())) {
        log.error(
            "{} delivery refuses Prudence's configuration (credentials/endpoint); records are "
                + "queued until fixed: {}",
            target.name(),
            e.getMessage());
        if (target.reportsFailures()) {
          tx.executeWithoutResult(
              s -> listeners.forEach(l -> l.configurationFailing(target.name(), e.getMessage())));
        }
      }
    } else if (outcome[0] == Delivery.Status.DEAD) {
      log.error(
          "{} record {} dead-lettered ({}): {}",
          target.name(),
          id,
          failure == DeliveryFailure.PERMANENT ? "rejected" : "out of attempts",
          e.getMessage());
    } else {
      log.warn("{} delivery failed for {} (will retry): {}", target.name(), id, e.getMessage());
    }
  }

  private <E> void update(DeliveryTarget<E> target, String id, Consumer<E> change) {
    tx.executeWithoutResult(
        s ->
            target
                .find(id)
                .ifPresent(
                    record -> {
                      change.accept(record);
                      target.save(record);
                    }));
  }
}
