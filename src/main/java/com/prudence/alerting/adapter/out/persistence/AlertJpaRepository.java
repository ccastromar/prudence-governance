package com.prudence.alerting.adapter.out.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AlertJpaRepository extends JpaRepository<AlertJpaEntity, String> {
    boolean existsByDedupKey(String dedupKey);

    List<AlertJpaEntity> findAllByOrderByCreatedAtDesc(Limit limit);

    @Query("select e from AlertJpaEntity e where e.delivery.deliveredAt is null and e.delivery.deadAt is null "
            + "and e.delivery.nextAttemptAt <= :now order by e.createdAt")
    List<AlertJpaEntity> due(@Param("now") String now, Limit limit);

    /// Lease a due alert (see DeliveryTarget#claim): only one worker can match `expected`.
    @Modifying
    @Query("update AlertJpaEntity e set e.delivery.nextAttemptAt = :leaseUntil where e.id = :id "
            + "and e.delivery.deliveredAt is null and e.delivery.deadAt is null and e.delivery.nextAttemptAt = :expected")
    int claim(@Param("id") String id, @Param("expected") String expected, @Param("leaseUntil") String leaseUntil);

    @Query("select e from AlertJpaEntity e where e.delivery.deadAt is not null order by e.delivery.deadAt desc")
    List<AlertJpaEntity> deadLettered(Limit limit);
}
