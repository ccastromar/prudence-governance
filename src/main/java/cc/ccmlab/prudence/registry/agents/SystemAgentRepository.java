package cc.ccmlab.prudence.registry.agents;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemAgentRepository extends JpaRepository<SystemAgentJpaEntity, String> {
  List<SystemAgentJpaEntity> findByAiSystemIdOrderByAgentId(String aiSystemId);

  List<SystemAgentJpaEntity> findByAgentIdOrderByAiSystemId(String agentId);

  void deleteByAiSystemIdAndAgentId(String aiSystemId, String agentId);
}
