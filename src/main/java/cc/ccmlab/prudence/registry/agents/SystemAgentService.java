package cc.ccmlab.prudence.registry.agents;

import cc.ccmlab.prudence.registry.application.port.in.AiSystemQueries;
import cc.ccmlab.prudence.registry.application.port.in.ReviewAiSystemUseCase;
import cc.ccmlab.prudence.registry.domain.AiSystem;
import cc.ccmlab.prudence.registry.domain.AiSystemId;
import cc.ccmlab.prudence.registry.domain.AiSystemNotFoundException;
import cc.ccmlab.prudence.registry.domain.RiskTier;
import cc.ccmlab.prudence.shared.exception.DomainException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// The agents that act for an AI system (`contract/identity/` section 5). Declaring them is part
/// of declaring the system (system.declare). A new agent widens what an approved high-risk system
/// may do, so adding one suspends it until it is approved again; removing one does not.
@Service
public class SystemAgentService {

  /// A workload name, OAuth client id or SPIFFE ID.
  private static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9._:@/+-]{1,255}");

  private final SystemAgentRepository agents;
  private final AiSystemQueries systems;
  private final ReviewAiSystemUseCase reviews;

  public SystemAgentService(
      SystemAgentRepository agents, AiSystemQueries systems, ReviewAiSystemUseCase reviews) {
    this.agents = agents;
    this.systems = systems;
    this.reviews = reviews;
  }

  public record Agent(String agentId, String declaredBy, String declaredAt) {}

  /// `suspended`: this declaration took an approved high-risk system out of service.
  public record Declaration(String aiSystemId, List<Agent> agents, boolean suspended) {}

  @Transactional(readOnly = true)
  public List<Agent> agentsOf(String aiSystemId) {
    return agents.findByAiSystemIdOrderByAgentId(aiSystemId).stream()
        .map(a -> new Agent(a.getAgentId(), a.getDeclaredBy(), a.getDeclaredAt()))
        .toList();
  }

  /// Replaces the system's agents with `agentIds`.
  @Transactional
  public Declaration declare(String aiSystemId, List<String> agentIds, String actor) {
    AiSystemId id = new AiSystemId(aiSystemId);
    AiSystem system = systems.findById(id).orElseThrow(() -> new AiSystemNotFoundException(id));
    Set<String> wanted = new TreeSet<>();
    for (String agent : agentIds == null ? List.<String>of() : agentIds) {
      String a = agent == null ? "" : agent.strip();
      if (!AGENT_ID.matcher(a).matches())
        throw new DomainException("invalid agent id: \"" + a + "\"");
      wanted.add(a);
    }
    Set<String> current = new TreeSet<>();
    agents.findByAiSystemIdOrderByAgentId(aiSystemId).forEach(a -> current.add(a.getAgentId()));

    Set<String> added = new TreeSet<>(wanted);
    added.removeAll(current);
    Set<String> removed = new TreeSet<>(current);
    removed.removeAll(wanted);

    removed.forEach(a -> agents.deleteByAiSystemIdAndAgentId(aiSystemId, a));
    String now = Instant.now().toString();
    added.forEach(
        a ->
            agents.save(
                new SystemAgentJpaEntity(UUID.randomUUID().toString(), aiSystemId, a, actor, now)));

    boolean suspend = !added.isEmpty() && system.riskTier() == RiskTier.HIGH && system.isApproved();
    if (suspend)
      reviews.suspend(
          id,
          actor == null ? "prudence" : actor,
          "Agents added to an approved high-risk system; new approval required: " + added);
    return new Declaration(aiSystemId, agentsOf(aiSystemId), suspend);
  }
}
