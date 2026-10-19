package cc.ccmlab.prudence.incident.application.port.out;

import cc.ccmlab.prudence.incident.domain.IncidentEvent;
import java.util.List;

/// Append-only incident timeline; each event is delivered to Ernest after commit.
public interface IncidentEventLog {
  void append(IncidentEvent event);

  List<IncidentEvent> findByIncident(String incidentId);
}
