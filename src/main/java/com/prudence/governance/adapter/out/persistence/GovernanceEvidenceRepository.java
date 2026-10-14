package com.prudence.governance.adapter.out.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface GovernanceEvidenceRepository extends JpaRepository<GovernanceEvidenceJpaEntity, String> { List<GovernanceEvidenceJpaEntity> findByCaseIdOrderByAddedAtAsc(String caseId); long countByCaseId(String caseId); }
