package com.prudence.incident.application.port.in;

import java.time.Instant;
import java.util.List;

/// Driving port for the deadline sweep: alert before an Art. 73 report falls due, and when
/// it is overdue. One transaction per incident.
public interface IncidentDeadlineUseCase {
    List<String> unreportedIncidentIds();

    void checkDeadline(String incidentId, Instant now);
}
