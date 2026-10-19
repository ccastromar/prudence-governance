package cc.ccmlab.prudence.monitoring;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapaRepository extends JpaRepository<CapaJpaEntity, String> {
  List<CapaJpaEntity> findByAiSystemIdOrderByDueAtAsc(String id);
}
