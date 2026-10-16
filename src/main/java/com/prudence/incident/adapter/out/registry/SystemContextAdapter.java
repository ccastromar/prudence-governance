package com.prudence.incident.adapter.out.registry;

import com.prudence.incident.application.port.out.SystemContextPort;
import com.prudence.incident.domain.Incident;
import com.prudence.registry.application.port.out.AiSystemRepository;
import com.prudence.registry.domain.AiSystemId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/// ACL adapter: projects the registry aggregate onto what the incident register needs.
@Component
public class SystemContextAdapter implements SystemContextPort {

  private final AiSystemRepository registry;

  public SystemContextAdapter(AiSystemRepository registry) {
    this.registry = registry;
  }

  @Override
  public Optional<SystemContext> find(String aiSystemId) {
    if (aiSystemId == null || aiSystemId.isBlank()) return Optional.empty();
    return registry
        .findById(new AiSystemId(aiSystemId))
        .map(
            s ->
                new SystemContext(
                    s.id().value(),
                    s.name(),
                    s.owner().email(),
                    s.riskTier().name(),
                    Incident.RegulatoryRole.valueOf(s.classification().regulatoryRole().name())));
  }
}
