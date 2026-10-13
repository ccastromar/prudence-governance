package com.prudence.posture.adapter.in.scheduling;

import com.prudence.alerting.application.port.in.RaiseAlert;
import com.prudence.alerting.application.port.in.RaiseAlert.AlertRequest;
import com.prudence.alerting.application.port.in.RaiseAlert.Audience;
import com.prudence.alerting.domain.AlertKind;
import com.prudence.posture.application.port.in.DriftQuery;
import com.prudence.posture.domain.Drift;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/// Turns policy drift into alerts: periodically recomputes drift and alerts the governance team
/// about each ungoverned system. The dedup key is the system *and* its approval state, so a
/// system is alerted once while it stays in that state — not on every new execution — and
/// again if it drifts from a different state later.
@Component
public class DriftAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(DriftAlertScheduler.class);

    private final DriftQuery drift;
    private final RaiseAlert alerts;

    public DriftAlertScheduler(DriftQuery drift, RaiseAlert alerts) {
        this.drift = drift;
        this.alerts = alerts;
    }

    @Scheduled(fixedDelayString = "${prudence.alerts.drift-check-ms:900000}",
               initialDelayString = "${prudence.alerts.drift-initial-delay-ms:120000}")
    public void check() {
        Drift report;
        try {
            report = drift.compute();
        } catch (Exception e) {
            log.warn("drift check failed: {}", e.getMessage());
            return;
        }
        for (Drift.Finding f : report.findings()) {
            if (!"VIOLATION".equals(f.severity())) continue;
            try {
                alerts.raise(new AlertRequest(AlertKind.DRIFT_VIOLATION, "DRIFT:" + f.aiSystemId() + ":" + f.approvalStatus(),
                        Audience.GOVERNANCE, List.of(), f.aiSystemId(),
                        "Ungoverned executions: " + f.name(),
                        "Policy drift detected for \"" + f.name() + "\" (" + f.aiSystemId() + ", approval state "
                                + f.approvalStatus() + "): " + f.detail() + "\n\nExecutions are being recorded for a "
                                + "system that is not approved to run. Investigate which runtime bypassed or ignored "
                                + "the PDP and stop it."));
            } catch (Exception e) {
                log.warn("could not raise drift alert for {}: {}", f.aiSystemId(), e.getMessage());
            }
        }
    }
}
