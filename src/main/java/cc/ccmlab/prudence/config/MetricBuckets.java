package cc.ccmlab.prudence.config;

import java.time.Duration;

/// Histogram buckets of Prudence's latency metrics (the suite's observability conventions):
/// they hold the thresholds the service-level indicators are measured on, so an indicator is a
/// bucket over the count rather than a guess between buckets.
public final class MetricBuckets {

  /// A decision and its policy evaluation, up to the gate's 5 s timeout (F1, admission).
  public static final Duration[] DECISION = {
    Duration.ofMillis(5),
    Duration.ofMillis(10),
    Duration.ofMillis(25),
    Duration.ofMillis(50),
    Duration.ofMillis(100),
    Duration.ofMillis(250),
    Duration.ofMillis(500),
    Duration.ofSeconds(1),
    Duration.ofMillis(2500),
    Duration.ofSeconds(5)
  };

  private MetricBuckets() {}
}
