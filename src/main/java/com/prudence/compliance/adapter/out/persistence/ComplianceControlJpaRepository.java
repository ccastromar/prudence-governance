package com.prudence.compliance.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplianceControlJpaRepository
    extends JpaRepository<ComplianceControlJpaEntity, String> {
  List<ComplianceControlJpaEntity> findByAiSystemIdOrderByProfileIdAscControlKeyAsc(
      String aiSystemId);

  Optional<ComplianceControlJpaEntity> findByIdAndAiSystemId(String id, String aiSystemId);

  boolean existsByAiSystemIdAndProfileId(String aiSystemId, String profileId);
}
