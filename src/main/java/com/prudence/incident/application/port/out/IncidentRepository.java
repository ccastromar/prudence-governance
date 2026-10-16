package com.prudence.incident.application.port.out;

import com.prudence.incident.domain.Incident;
import java.util.List;
import java.util.Optional;

public interface IncidentRepository {
  Incident save(Incident incident);

  Optional<Incident> findById(String id);

  /// Locks the incident for the rest of the transaction (concurrent updates are serialised).
  Optional<Incident> findByIdForUpdate(String id);

  List<Incident> findAllNewestFirst();

  /// Incidents not yet closed — what posture and the deadline sweep look at.
  List<Incident> findUnclosed();
}
