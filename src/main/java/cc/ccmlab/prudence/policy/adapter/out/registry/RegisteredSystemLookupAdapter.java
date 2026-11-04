package cc.ccmlab.prudence.policy.adapter.out.registry;

import cc.ccmlab.prudence.policy.application.port.out.RegisteredSystemLookup;
import cc.ccmlab.prudence.registry.agents.SystemAgentJpaEntity;
import cc.ccmlab.prudence.registry.agents.SystemAgentRepository;
import cc.ccmlab.prudence.registry.application.port.out.AiSystemRepository;
import cc.ccmlab.prudence.registry.domain.AiSystemId;
import cc.ccmlab.prudence.registry.domain.ApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/// ACL adapter: satisfies the policy context's lookup port by delegating to the registry
/// repository and projecting only the governance facts the PDP needs.
@Component
public class RegisteredSystemLookupAdapter implements RegisteredSystemLookup {

  private final AiSystemRepository registry;
  private final SystemAgentRepository agents;

  public RegisteredSystemLookupAdapter(AiSystemRepository registry, SystemAgentRepository agents) {
    this.registry = registry;
    this.agents = agents;
  }

  @Override
  public List<String> agentsOf(String aiSystemId) {
    return agents.findByAiSystemIdOrderByAgentId(aiSystemId).stream()
        .map(SystemAgentJpaEntity::getAgentId)
        .toList();
  }

  @Override
  public List<String> systemsOf(String agent) {
    return agents.findByAgentIdOrderByAiSystemId(agent).stream()
        .map(SystemAgentJpaEntity::getAiSystemId)
        .toList();
  }

  @Override
  public Optional<SystemRiskView> find(String aiSystemId) {
    return registry
        .findById(new AiSystemId(aiSystemId))
        .map(
            s ->
                new SystemRiskView(
                    s.id().value(),
                    s.riskTier(),
                    s.isApproved(),
                    s.classification().systemVersion(),
                    s.classification().modelHash(),
                    s.approvalStatus() == ApprovalStatus.EXPIRED || s.hasLapsed(Instant.now()),
                    s.name()));
  }
}
