package com.prudence.change; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface ChangeAssessmentRepository extends JpaRepository<ChangeAssessmentJpaEntity,String>{List<ChangeAssessmentJpaEntity> findByAiSystemIdOrderByAssessedAtDesc(String aiSystemId);}
