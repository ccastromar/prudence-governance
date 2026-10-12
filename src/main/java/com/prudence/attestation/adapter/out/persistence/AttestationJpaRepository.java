package com.prudence.attestation.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttestationJpaRepository extends JpaRepository<AttestationJpaEntity, String> {
    List<AttestationJpaEntity> findAllByOrderByGeneratedAtDesc();
}
