package cc.ccmlab.prudence.impact;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FriaRepository extends JpaRepository<FriaJpaEntity, String> {
  List<FriaJpaEntity> findByAiSystemIdOrderBySignedAtDesc(String id);

  boolean existsByAiSystemId(String id);
}
