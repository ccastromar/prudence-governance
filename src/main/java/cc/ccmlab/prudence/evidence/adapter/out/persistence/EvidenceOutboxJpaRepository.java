package cc.ccmlab.prudence.evidence.adapter.out.persistence;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvidenceOutboxJpaRepository
    extends JpaRepository<EvidenceOutboxJpaEntity, String> {
  @Query(
      "select e from EvidenceOutboxJpaEntity e where e.delivery.deliveredAt is null and"
          + " e.delivery.deadAt is null and e.delivery.nextAttemptAt <= :now order by e.createdAt")
  List<EvidenceOutboxJpaEntity> due(@Param("now") String now, Limit limit);

  /// Lease a due event (see DeliveryTarget#claim): only one worker can match `expected`.
  @Modifying
  @Query(
      "update EvidenceOutboxJpaEntity e set e.delivery.nextAttemptAt = :leaseUntil where e.id = :id"
          + " and e.delivery.deliveredAt is null and e.delivery.deadAt is null and"
          + " e.delivery.nextAttemptAt = :expected")
  int claim(
      @Param("id") String id,
      @Param("expected") String expected,
      @Param("leaseUntil") String leaseUntil);

  /// Per-system delivery backlog: [aiSystemId, dead count, overdue-pending count]. "Overdue"
  /// means undelivered and created before `overdueBefore` — evidence that should have landed.
  @Query(
      "select e.aiSystemId, sum(case when e.delivery.deadAt is not null then 1 else 0 end),"
          + " sum(case when e.delivery.deadAt is null and e.createdAt < :overdueBefore then 1 else"
          + " 0 end) from EvidenceOutboxJpaEntity e where e.delivery.deliveredAt is null group by"
          + " e.aiSystemId")
  List<Object[]> undeliveredBySystem(@Param("overdueBefore") String overdueBefore);

  @Query(
      "select e from EvidenceOutboxJpaEntity e where e.delivery.deadAt is not null order by"
          + " e.delivery.deadAt desc")
  List<EvidenceOutboxJpaEntity> deadLettered(Limit limit);

  /// The systems among `ids` with at least one decision acknowledged by the evidence store.
  @org.springframework.data.jpa.repository.Query(
      "select distinct e.aiSystemId from EvidenceOutboxJpaEntity e"
          + " where e.aiSystemId in :ids and e.delivery.deliveredAt is not null")
  java.util.List<String> deliveredSystems(
      @org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);
}
