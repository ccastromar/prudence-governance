package cc.ccmlab.prudence.oversight;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HumanOverrideRepository extends JpaRepository<HumanOverrideJpaEntity, String> {
  List<HumanOverrideJpaEntity> findByAiSystemIdOrderByOccurredAtDesc(String id);
}
