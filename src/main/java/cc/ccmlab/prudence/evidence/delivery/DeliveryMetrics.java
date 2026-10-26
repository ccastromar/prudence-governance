package cc.ccmlab.prudence.evidence.delivery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// Operational metrics of the outbound delivery pipeline. Tags are limited to the target name
/// (a fixed handful) and a small outcome set; record, system and decision ids never become tags.
///
/// - `prudence.delivery.records{target,outcome}`: delivered | retry | dead | configuration.
/// - `prudence.delivery.send{target,outcome}`: time of one call to the target (ok | error).
/// - `prudence.delivery.due{target}`: records due now, as seen by the last pass (capped by the
///   batch size). A value that does not go down is a backlog.
/// - `prudence.delivery.backlog.age{target}`: seconds since the target last had nothing due; 0
///   when it is keeping up. Grows while delivery is stuck.
/// - `prudence.delivery.delay{target}`: from a record being written to its delivery, retries
///   included; the buckets hold the 15-minute freshness threshold (contract/observability, 8).
final class DeliveryMetrics {
  private final MeterRegistry registry;
  private final Map<String, AtomicLong> due = new ConcurrentHashMap<>();
  /// Epoch millis since which the target has always had something due; 0 when it caught up.
  private final Map<String, AtomicLong> backlogSince = new ConcurrentHashMap<>();

  DeliveryMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  void records(String target, String outcome, int count) {
    if (count > 0)
      registry
          .counter("prudence.delivery.records", "target", target, "outcome", outcome)
          .increment(count);
  }

  /// Upper bounds of `prudence.delivery.delay`'s buckets, as Ernest's chaining delay.
  static final Duration[] DELAY_BUCKETS = {
    Duration.ofMillis(500),
    Duration.ofSeconds(1),
    Duration.ofSeconds(2),
    Duration.ofSeconds(5),
    Duration.ofSeconds(10),
    Duration.ofSeconds(30),
    Duration.ofMinutes(1),
    Duration.ofMinutes(2),
    Duration.ofMinutes(5),
    Duration.ofMinutes(15),
    Duration.ofHours(1)
  };

  void delay(String target, Duration waited) {
    if (waited == null || waited.isNegative()) return;
    Timer.builder("prudence.delivery.delay")
        .description("From a record being written to its delivery")
        .tag("target", target)
        .serviceLevelObjectives(DELAY_BUCKETS)
        .register(registry)
        .record(waited);
  }

  void send(String target, boolean ok, long nanos) {
    Timer.builder("prudence.delivery.send")
        .tags("target", target, "outcome", ok ? "ok" : "error")
        .publishPercentileHistogram()
        .register(registry)
        .record(Duration.ofNanos(nanos));
  }

  /// What a pass found due; `count == 0` means the target caught up.
  void due(String target, int count) {
    gauge(due, "prudence.delivery.due", target).set(count);
    AtomicLong since = backlogSince.computeIfAbsent(target, t -> backlogAge(t));
    if (count == 0) since.set(0);
    else since.compareAndSet(0, System.currentTimeMillis());
  }

  private AtomicLong backlogAge(String target) {
    AtomicLong since = new AtomicLong();
    Gauge.builder(
            "prudence.delivery.backlog.age",
            since,
            s -> s.get() == 0 ? 0 : (System.currentTimeMillis() - s.get()) / 1000.0)
        .tag("target", target)
        .baseUnit("seconds")
        .register(registry);
    return since;
  }

  private AtomicLong gauge(Map<String, AtomicLong> values, String name, String target) {
    return values.computeIfAbsent(
        target,
        t -> {
          AtomicLong value = new AtomicLong();
          Gauge.builder(name, value, AtomicLong::get).tag("target", t).register(registry);
          return value;
        });
  }
}
