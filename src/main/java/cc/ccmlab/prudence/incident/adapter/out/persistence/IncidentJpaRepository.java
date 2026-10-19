package cc.ccmlab.prudence.incident.adapter.out.persistence;

import cc.ccmlab.prudence.incident.domain.IncidentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface IncidentJpaRepository extends JpaRepository<IncidentJpaEntity, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from IncidentJpaEntity e where e.id = :id")
  Optional<IncidentJpaEntity> findLockedById(@Param("id") String id);

  List<IncidentJpaEntity> findAllByOrderByDetectedAtDesc();

  List<IncidentJpaEntity> findByStatusNot(IncidentStatus status);
}
