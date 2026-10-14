package com.prudence.governance.adapter.out.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface GovernanceCaseRepository extends JpaRepository<GovernanceCaseJpaEntity, String> { List<GovernanceCaseJpaEntity> findAllByOrderByOpenedAtDesc(); }
