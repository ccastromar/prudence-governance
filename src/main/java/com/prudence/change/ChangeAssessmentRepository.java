package com.prudence.change;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeAssessmentRepository
    extends JpaRepository<ChangeAssessmentJpaEntity, String> {
  List<ChangeAssessmentJpaEntity> findByAiSystemIdOrderByAssessedAtDesc(String aiSystemId);
}
