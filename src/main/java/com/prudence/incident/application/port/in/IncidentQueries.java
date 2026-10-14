package com.prudence.incident.application.port.in;

import com.prudence.incident.domain.Incident;
import com.prudence.incident.domain.IncidentEvent;
import java.util.List;
import java.util.Optional;

public interface IncidentQueries {
  List<Incident> list();

  Optional<Incident> get(String id);

  List<IncidentEvent> timeline(String id);
}
