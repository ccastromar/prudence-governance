package cc.ccmlab.prudence.policy.adapter.out.audit;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PolicyAuditRepository extends JpaRepository<PolicyAuditEntity, String> {
  @Query(
      "select e from PolicyAuditEntity e where e.delivery.deliveredAt is null and e.delivery.deadAt is null and e.delivery.nextAttemptAt <= :now order by e.occurredAt")
  List<PolicyAuditEntity> due(@Param("now") String now, Limit limit);

  @Modifying
  @Query(
      "update PolicyAuditEntity e set e.delivery.nextAttemptAt = :leaseUntil where e.id = :id and e.delivery.deliveredAt is null and e.delivery.deadAt is null and e.delivery.nextAttemptAt = :expected")
  int claim(
      @Param("id") String id,
      @Param("expected") String expected,
      @Param("leaseUntil") String leaseUntil);

  @Query(
      "select e from PolicyAuditEntity e where e.delivery.deadAt is not null order by e.delivery.deadAt desc")
  List<PolicyAuditEntity> deadLettered(Limit limit);
}
