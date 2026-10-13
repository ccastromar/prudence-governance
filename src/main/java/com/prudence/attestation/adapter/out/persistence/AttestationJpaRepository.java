package com.prudence.attestation.adapter.out.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AttestationJpaRepository extends JpaRepository<AttestationJpaEntity, String> {
    List<AttestationJpaEntity> findAllByOrderByGeneratedAtDesc();

    @Query("select e from AttestationJpaEntity e where e.delivery.deliveredAt is null and e.delivery.deadAt is null "
            + "and e.delivery.nextAttemptAt <= :now order by e.generatedAt")
    List<AttestationJpaEntity> due(@Param("now") String now, Limit limit);

    /// Lease a due attestation (see DeliveryTarget#claim): only one worker can match `expected`.
    @Modifying
    @Query("update AttestationJpaEntity e set e.delivery.nextAttemptAt = :leaseUntil where e.id = :id "
            + "and e.delivery.deliveredAt is null and e.delivery.deadAt is null and e.delivery.nextAttemptAt = :expected")
    int claim(@Param("id") String id, @Param("expected") String expected, @Param("leaseUntil") String leaseUntil);

    @Query("select e from AttestationJpaEntity e where e.delivery.deadAt is not null order by e.delivery.deadAt desc")
    List<AttestationJpaEntity> deadLettered(Limit limit);
}
