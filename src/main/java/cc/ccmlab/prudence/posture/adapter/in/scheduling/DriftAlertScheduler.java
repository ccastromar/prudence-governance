package cc.ccmlab.prudence.posture.adapter.in.scheduling;

import cc.ccmlab.prudence.alerting.application.port.in.RaiseAlert;
import cc.ccmlab.prudence.alerting.application.port.in.RaiseAlert.AlertRequest;
import cc.ccmlab.prudence.alerting.application.port.in.RaiseAlert.Audience;
import cc.ccmlab.prudence.alerting.domain.AlertKind;
import cc.ccmlab.prudence.posture.application.port.in.DriftQuery;
import cc.ccmlab.prudence.posture.domain.Drift;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/// Turns policy drift into alerts: periodically recomputes drift and alerts the governance team
/// about each ungoverned system. The dedup key is the system *and* its approval state, so a
/// system is alerted once while it stays in that state — not on every new execution — and
/// again if it drifts from a different state later.
@Component
public class DriftAlertScheduler {

  private static final Logger log = LoggerFactory.getLogger(DriftAlertScheduler.class);

  private final DriftQuery drift;
  private final RaiseAlert alerts;
  private final MeterRegistry meters;

  public DriftAlertScheduler(DriftQuery drift, RaiseAlert alerts, MeterRegistry meters) {
    this.drift = drift;
    this.alerts = alerts;
    this.meters = meters;
  }

  /// `prudence.drift.scans{outcome}` (contract/observability, F4): complete, incomplete (some
  /// execution evidence could not be read) or failed. Oversight on complete data is the share of
  /// complete scans.
  private void count(String outcome) {
    meters.counter("prudence.drift.scans", "outcome", outcome).increment();
  }

  @Scheduled(
      fixedDelayString = "${prudence.alerts.drift-check-ms:900000}",
      initialDelayString = "${prudence.alerts.drift-initial-delay-ms:120000}")
  public void check() {
    Drift report;
    try {
      report = drift.compute();
    } catch (Exception e) {
      count("failed");
      log.warn("drift check failed: {}", e.getMessage());
      return;
    }
    count(report.complete() ? "complete" : "incomplete");
    if (!report.complete()) {
      // A blind drift check must not pass for a clean one: tell operations, once a day.
      String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
      try {
        alerts.raise(
            new AlertRequest(
                AlertKind.DRIFT_SCAN_INCOMPLETE,
                "DRIFT_INCOMPLETE:" + day,
                Audience.OPERATIONS,
                List.of(),
                null,
                "Policy-drift check is incomplete",
                "The drift check could not read all execution evidence from Ernest, so executions"
                    + " by non-approved systems may be going undetected. Fix the cause (Ernest"
                    + " reachable, not throttling Prudence) — until then the absence of drift"
                    + " alerts means nothing.\n\n"
                    + "Detail: "
                    + report.scanDetail()));
      } catch (Exception e) {
        log.warn("could not raise drift-incomplete alert: {}", e.getMessage());
      }
    }
    for (Drift.Finding f : report.findings()) {
      if (!"VIOLATION".equals(f.severity())) continue;
      try {
        alerts.raise(
            new AlertRequest(
                AlertKind.DRIFT_VIOLATION,
                "DRIFT:" + f.aiSystemId() + ":" + f.approvalStatus(),
                Audience.GOVERNANCE,
                List.of(),
                f.aiSystemId(),
                "Ungoverned executions: " + f.name(),
                "Policy drift detected for \""
                    + f.name()
                    + "\" ("
                    + f.aiSystemId()
                    + ", approval state "
                    + f.approvalStatus()
                    + "): "
                    + f.detail()
                    + "\n\n"
                    + "Executions are being recorded for a system that is not approved to run."
                    + " Investigate which runtime bypassed or ignored the PDP and stop it."));
      } catch (Exception e) {
        log.warn("could not raise drift alert for {}: {}", f.aiSystemId(), e.getMessage());
      }
    }
  }
}
