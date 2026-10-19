package cc.ccmlab.prudence.incident.adapter.out.persistence;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentEventJpaRepository extends JpaRepository<IncidentEventJpaEntity, String> {
  List<IncidentEventJpaEntity> findByIncidentIdOrderByAtAsc(String incidentId);

  @Query(
      "select e from IncidentEventJpaEntity e where e.delivery.deliveredAt is null and"
          + " e.delivery.deadAt is null and e.delivery.nextAttemptAt <= :now order by e.at")
  List<IncidentEventJpaEntity> due(@Param("now") String now, Limit limit);

  /// Lease a due event (see DeliveryTarget#claim): only one worker can match `expected`.
  @Modifying
  @Query(
      "update IncidentEventJpaEntity e set e.delivery.nextAttemptAt = :leaseUntil where e.id = :id"
          + " and e.delivery.deliveredAt is null and e.delivery.deadAt is null and"
          + " e.delivery.nextAttemptAt = :expected")
  int claim(
      @Param("id") String id,
      @Param("expected") String expected,
      @Param("leaseUntil") String leaseUntil);

  /// Per-system backlog: [aiSystemId, dead count, overdue-pending count]; `at` is
  // Instant.toString().
  @Query(
      "select e.aiSystemId, sum(case when e.delivery.deadAt is not null then 1 else 0 end),"
          + " sum(case when e.delivery.deadAt is null and e.at < :overdueBefore then 1 else 0 end)"
          + " from IncidentEventJpaEntity e where e.delivery.deliveredAt is null group by"
          + " e.aiSystemId")
  List<Object[]> undeliveredBySystem(@Param("overdueBefore") String overdueBefore);

  @Query(
      "select e from IncidentEventJpaEntity e where e.delivery.deadAt is not null order by"
          + " e.delivery.deadAt desc")
  List<IncidentEventJpaEntity> deadLettered(Limit limit);
}
