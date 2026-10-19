package cc.ccmlab.prudence.governance.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GovernanceCaseRepository extends JpaRepository<GovernanceCaseJpaEntity, String> {
  List<GovernanceCaseJpaEntity> findAllByOrderByOpenedAtDesc();
}
