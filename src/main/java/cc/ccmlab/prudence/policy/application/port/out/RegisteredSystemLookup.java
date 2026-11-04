package cc.ccmlab.prudence.policy.application.port.out;

import cc.ccmlab.prudence.registry.domain.RiskTier;
import java.util.List;
import java.util.Optional;

/// Anti-corruption boundary over the registry context: the policy context sees only the
/// governance facts it needs, not the registry's aggregate.
public interface RegisteredSystemLookup {
  Optional<SystemRiskView> find(String aiSystemId);

  /// The agents a system declares (`contract/identity/` section 5); empty when it declares none.
  default List<String> agentsOf(String aiSystemId) {
    return List.of();
  }

  /// The systems that declare `agent`.
  default List<String> systemsOf(String agent) {
    return List.of();
  }

  /// `approvalExpired`: the system had an approval whose validity ran out (not renewed).
  record SystemRiskView(
      String aiSystemId,
      RiskTier riskTier,
      boolean approved,
      String systemVersion,
      String modelHash,
      boolean approvalExpired,
      String name) {
    public SystemRiskView(
        String aiSystemId,
        RiskTier riskTier,
        boolean approved,
        String systemVersion,
        String modelHash,
        boolean approvalExpired) {
      this(aiSystemId, riskTier, approved, systemVersion, modelHash, approvalExpired, null);
    }

    public SystemRiskView(String aiSystemId, RiskTier riskTier, boolean approved) {
      this(aiSystemId, riskTier, approved, "unknown", "unknown", false);
    }
  }
}
