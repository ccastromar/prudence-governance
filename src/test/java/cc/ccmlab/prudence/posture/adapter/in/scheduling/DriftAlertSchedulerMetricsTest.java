package cc.ccmlab.prudence.posture.adapter.in.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cc.ccmlab.prudence.posture.domain.Drift;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Each scheduled drift scan is counted by outcome: the F4 indicator (oversight on complete
/// data, contract/observability section 8) is the share of complete scans.
class DriftAlertSchedulerMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private double scans(String outcome) {
    var counter = registry.find("prudence.drift.scans").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void scansAreCountedAsCompleteIncompleteOrFailed() {
    Drift complete = new Drift("now", false, true, "all read", List.of());
    Drift incomplete = new Drift("now", false, false, "Ernest throttled", List.of());
    new DriftAlertScheduler(() -> complete, request -> {}, registry).check();
    new DriftAlertScheduler(() -> incomplete, request -> {}, registry).check();
    new DriftAlertScheduler(
            () -> {
              throw new IllegalStateException("database down");
            },
            request -> {},
            registry)
        .check();

    assertEquals(1, scans("complete"));
    assertEquals(1, scans("incomplete"));
    assertEquals(1, scans("failed"));
  }
}
