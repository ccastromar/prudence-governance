package com.prudence.registry.adapter.out.persistence;

import com.prudence.registry.domain.ApprovalStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface AiSystemJpaRepository extends JpaRepository<AiSystemJpaEntity, String> {

    /// Row lock (SELECT ... FOR UPDATE) held until the transaction ends.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AiSystemJpaEntity e where e.id = :id")
    Optional<AiSystemJpaEntity> findLockedById(@Param("id") String id);

    @Query("select e.id from AiSystemJpaEntity e where e.approvalStatus = :status")
    List<String> findIdsByApprovalStatus(@Param("status") ApprovalStatus status);
}
