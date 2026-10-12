package com.prudence.registry.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewEventJpaRepository extends JpaRepository<ReviewEventJpaEntity, String> {
    List<ReviewEventJpaEntity> findByAiSystemIdOrderByAtDesc(String aiSystemId);
    List<ReviewEventJpaEntity> findAllByOrderByAtDesc();
}
