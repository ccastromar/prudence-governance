package cc.ccmlab.prudence.oversight;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HumanAssignmentRepository extends JpaRepository<HumanAssignmentJpaEntity, String> {
  List<HumanAssignmentJpaEntity> findByAiSystemId(String id);
}
