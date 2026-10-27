package cc.ccmlab.prudence.policy.adapter.in.web;

import cc.ccmlab.prudence.config.MetricBuckets;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/// `prudence.decision.duration{verdict, front_door}` (the suite's observability conventions): the
/// time to answer one decision, recorded, at either front door. The buckets hold the latency
/// thresholds the admission objective (section 8, F1) is measured on, up to the gate's 5 s
/// timeout. A decision that fails before it has a verdict counts as `ERROR`.
@Component
public class DecisionMetrics {

  private final MeterRegistry registry;

  public DecisionMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  RecordedDecisions.Recorded time(String frontDoor, Supplier<RecordedDecisions.Recorded> decide) {
    long started = System.nanoTime();
    String verdict = "ERROR";
    try {
      RecordedDecisions.Recorded recorded = decide.get();
      verdict = verdict(recorded.decision());
      return recorded;
    } finally {
      Timer.builder("prudence.decision.duration")
          .description("Time to answer and record one decision")
          .tags("verdict", verdict, "front_door", frontDoor)
          .serviceLevelObjectives(MetricBuckets.DECISION)
          .register(registry)
          .record(Duration.ofNanos(System.nanoTime() - started));
    }
  }

  static String verdict(PolicyDecision decision) {
    return switch (decision) {
      case PolicyDecision.Allow ignored -> "ALLOW";
      case PolicyDecision.Deny ignored -> "DENY";
      case PolicyDecision.ApprovalRequired ignored -> "APPROVAL_REQUIRED";
      case PolicyDecision.Error ignored -> "ERROR";
    };
  }
}
