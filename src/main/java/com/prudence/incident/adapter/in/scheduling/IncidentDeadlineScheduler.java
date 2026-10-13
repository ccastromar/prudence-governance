package com.prudence.incident.adapter.in.scheduling;

import com.prudence.incident.application.port.in.IncidentDeadlineUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/// Driving adapter: alerts 24h before an Art. 73 report falls due, and again once it is
/// overdue. Deadlines can be as short as 2 days, so this runs every 15 minutes by default.
@Component
public class IncidentDeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(IncidentDeadlineScheduler.class);

    private final IncidentDeadlineUseCase deadlines;

    public IncidentDeadlineScheduler(IncidentDeadlineUseCase deadlines) {
        this.deadlines = deadlines;
    }

    @Scheduled(fixedDelayString = "${prudence.incidents.deadline-check-ms:900000}",
               initialDelayString = "${prudence.incidents.deadline-initial-delay-ms:60000}")
    public void check() {
        Instant now = Instant.now();
        for (String id : deadlines.unreportedIncidentIds()) {
            try {
                deadlines.checkDeadline(id, now);
            } catch (Exception e) {
                log.warn("incident deadline check failed for {}: {}", id, e.getMessage());
            }
        }
    }
}
