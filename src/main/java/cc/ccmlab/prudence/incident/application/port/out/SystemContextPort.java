package cc.ccmlab.prudence.incident.application.port.out;

import cc.ccmlab.prudence.incident.domain.Incident.RegulatoryRole;
import java.util.Optional;

/// ACL over the registry: the facts about an AI system the incident register needs.
public interface SystemContextPort {
  Optional<SystemContext> find(String aiSystemId);

  record SystemContext(
      String id, String name, String ownerEmail, String riskTier, RegulatoryRole role) {}
}
