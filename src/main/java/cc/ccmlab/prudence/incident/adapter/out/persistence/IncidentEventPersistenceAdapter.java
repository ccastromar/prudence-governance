package cc.ccmlab.prudence.incident.adapter.out.persistence;

import cc.ccmlab.prudence.incident.application.port.out.IncidentEventLog;
import cc.ccmlab.prudence.incident.domain.IncidentEvent;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class IncidentEventPersistenceAdapter implements IncidentEventLog {

  private final IncidentEventJpaRepository jpa;

  public IncidentEventPersistenceAdapter(IncidentEventJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public void append(IncidentEvent e) {
    jpa.save(
        new IncidentEventJpaEntity(
            e.id(), e.incidentId(), e.aiSystemId(), e.type(), e.actor(), e.at(), e.details()));
  }

  @Override
  public List<IncidentEvent> findByIncident(String incidentId) {
    return jpa.findByIncidentIdOrderByAtAsc(incidentId).stream()
        .map(IncidentEventPersistenceAdapter::toDomain)
        .toList();
  }

  public static IncidentEvent toDomain(IncidentEventJpaEntity e) {
    return new IncidentEvent(
        e.getId(),
        e.getIncidentId(),
        e.getAiSystemId(),
        e.getType(),
        e.getActor(),
        e.getAt(),
        e.getDetails(),
        e.getDelivery().status().name(),
        e.isSealed(),
        e.getBlockIndex());
  }
}
