package com.prudence.governance.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GovernanceCaseEventRepository
    extends JpaRepository<GovernanceCaseEventJpaEntity, String> {
  List<GovernanceCaseEventJpaEntity> findByCaseIdOrderByAtAsc(String caseId);
}
