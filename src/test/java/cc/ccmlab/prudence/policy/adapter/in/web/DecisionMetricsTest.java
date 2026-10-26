package cc.ccmlab.prudence.policy.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ccmlab.prudence.config.MetricBuckets;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DecisionMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final DecisionMetrics metrics = new DecisionMetrics(registry);

  @Test
  void aDecisionIsTimedByVerdictAndFrontDoorInTheObjectivesBuckets() {
    metrics.time(
        "decision_api",
        () -> new RecordedDecisions.Recorded("d-1", "v1", "sha", new PolicyDecision.Deny("no")));

    Timer timer =
        registry
            .find("prudence.decision.duration")
            .tags("verdict", "DENY", "front_door", "decision_api")
            .timer();
    assertEquals(1, timer.count());
    CountAtBucket[] buckets = timer.takeSnapshot().histogramCounts();
    assertEquals(MetricBuckets.DECISION.length, buckets.length);
    assertTrue(
        Arrays.stream(buckets)
            .anyMatch(b -> b.bucket(java.util.concurrent.TimeUnit.SECONDS) == 0.25),
        "the 250 ms threshold is a bucket");
  }

  @Test
  void aDecisionThatFailsBeforeItsVerdictCountsAsError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            metrics.time(
                "authzen",
                () -> {
                  throw new IllegalStateException("database down");
                }));
    assertEquals(
        1,
        registry
            .find("prudence.decision.duration")
            .tags("verdict", "ERROR", "front_door", "authzen")
            .timer()
            .count());
  }
}
