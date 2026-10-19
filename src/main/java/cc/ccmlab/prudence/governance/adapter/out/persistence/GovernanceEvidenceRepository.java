package cc.ccmlab.prudence.governance.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GovernanceEvidenceRepository
    extends JpaRepository<GovernanceEvidenceJpaEntity, String> {
  List<GovernanceEvidenceJpaEntity> findByCaseIdOrderByAddedAtAsc(String caseId);

  long countByCaseId(String caseId);
}
