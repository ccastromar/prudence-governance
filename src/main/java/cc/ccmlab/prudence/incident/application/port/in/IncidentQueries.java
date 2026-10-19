package cc.ccmlab.prudence.incident.application.port.in;

import cc.ccmlab.prudence.incident.domain.Incident;
import cc.ccmlab.prudence.incident.domain.IncidentEvent;
import java.util.List;
import java.util.Optional;

public interface IncidentQueries {
  List<Incident> list();

  Optional<Incident> get(String id);

  List<IncidentEvent> timeline(String id);
}
