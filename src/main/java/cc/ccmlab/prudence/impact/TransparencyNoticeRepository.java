package cc.ccmlab.prudence.impact;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransparencyNoticeRepository
    extends JpaRepository<TransparencyNoticeJpaEntity, String> {
  List<TransparencyNoticeJpaEntity> findByAiSystemIdOrderByDeployedAtDesc(String id);
}
