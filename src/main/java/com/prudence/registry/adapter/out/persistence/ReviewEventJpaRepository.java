package com.prudence.registry.adapter.out.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewEventJpaRepository extends JpaRepository<ReviewEventJpaEntity, String> {
    List<ReviewEventJpaEntity> findByAiSystemIdOrderByAtDesc(String aiSystemId);
    List<ReviewEventJpaEntity> findAllByOrderByAtDesc();

    @Query("select e from ReviewEventJpaEntity e where e.delivery.deliveredAt is null and e.delivery.deadAt is null "
            + "and e.delivery.nextAttemptAt <= :now order by e.at")
    List<ReviewEventJpaEntity> due(@Param("now") String now, Limit limit);

    /// Lease a due event (see DeliveryTarget#claim): only one worker can match `expected`.
    @Modifying
    @Query("update ReviewEventJpaEntity e set e.delivery.nextAttemptAt = :leaseUntil where e.id = :id "
            + "and e.delivery.deliveredAt is null and e.delivery.deadAt is null and e.delivery.nextAttemptAt = :expected")
    int claim(@Param("id") String id, @Param("expected") String expected, @Param("leaseUntil") String leaseUntil);

    /// Per-system review evidence backlog: [aiSystemId, dead count, overdue-pending count].
    /// `at` is the review time, written with Instant.toString(); the cut-off has the same form.
    @Query("select e.aiSystemId, "
            + "sum(case when e.delivery.deadAt is not null then 1 else 0 end), "
            + "sum(case when e.delivery.deadAt is null and e.at < :overdueBefore then 1 else 0 end) "
            + "from ReviewEventJpaEntity e where e.delivery.deliveredAt is null group by e.aiSystemId")
    List<Object[]> undeliveredBySystem(@Param("overdueBefore") String overdueBefore);
}
