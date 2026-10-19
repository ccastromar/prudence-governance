package cc.ccmlab.prudence.monitoring;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringSignalRepository
    extends JpaRepository<MonitoringSignalJpaEntity, String> {
  List<MonitoringSignalJpaEntity> findByAiSystemIdOrderByObservedAtDesc(String id);
}
