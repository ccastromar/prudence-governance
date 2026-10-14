package com.prudence.governance.adapter.out.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface GovernanceCaseEventRepository extends JpaRepository<GovernanceCaseEventJpaEntity, String> { List<GovernanceCaseEventJpaEntity> findByCaseIdOrderByAtAsc(String caseId); }
