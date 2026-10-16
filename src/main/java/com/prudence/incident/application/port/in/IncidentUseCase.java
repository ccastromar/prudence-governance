package com.prudence.incident.application.port.in;

import com.prudence.incident.domain.CausalLink;
import com.prudence.incident.domain.Incident;
import com.prudence.incident.domain.IncidentCategory;
import java.time.Instant;
import java.util.List;

/// Driving port: maintain the AI incident register.
public interface IncidentUseCase {

  /// `suspendSystem`: also take the AI system out of service now (audited in the registry).
  record OpenIncidentCommand(
      String aiSystemId,
      String title,
      String description,
      Instant occurredAt,
      Instant detectedAt,
      List<String> memberStates,
      IncidentCategory category,
      boolean widespreadInfringement,
      CausalLink causalLink,
      boolean suspendSystem) {}

  Incident open(OpenIncidentCommand command, String actor);

  Incident triage(
      String id,
      IncidentCategory category,
      boolean widespreadInfringement,
      CausalLink causalLink,
      String note,
      String actor);

  Incident recordInitialReport(String id, Incident.Report report, String actor);

  Incident recordFinalReport(
      String id, Incident.Report report, String rootCause, String correctiveActions, String actor);

  Incident close(String id, String note, String actor);
}
