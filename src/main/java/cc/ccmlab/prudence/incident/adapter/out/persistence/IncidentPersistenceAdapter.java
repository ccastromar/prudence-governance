package cc.ccmlab.prudence.incident.adapter.out.persistence;

import cc.ccmlab.prudence.incident.application.port.out.IncidentRepository;
import cc.ccmlab.prudence.incident.domain.Incident;
import cc.ccmlab.prudence.incident.domain.IncidentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class IncidentPersistenceAdapter implements IncidentRepository {

  private final IncidentJpaRepository jpa;

  public IncidentPersistenceAdapter(IncidentJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Incident save(Incident i) {
    return jpa.save(IncidentJpaEntity.from(i)).toDomain();
  }

  @Override
  public Optional<Incident> findById(String id) {
    return jpa.findById(id).map(IncidentJpaEntity::toDomain);
  }

  @Override
  public Optional<Incident> findByIdForUpdate(String id) {
    return jpa.findLockedById(id).map(IncidentJpaEntity::toDomain);
  }

  @Override
  public List<Incident> findAllNewestFirst() {
    return jpa.findAllByOrderByDetectedAtDesc().stream().map(IncidentJpaEntity::toDomain).toList();
  }

  @Override
  public List<Incident> findUnclosed() {
    return jpa.findByStatusNot(IncidentStatus.CLOSED).stream()
        .map(IncidentJpaEntity::toDomain)
        .toList();
  }
}
