package cc.ccmlab.prudence.policy.application.port.out;

import cc.ccmlab.prudence.registry.domain.RiskTier;
import java.util.Optional;

/// Anti-corruption boundary over the registry context: the policy context sees only the
/// governance facts it needs, not the registry's aggregate.
public interface RegisteredSystemLookup {
  Optional<SystemRiskView> find(String aiSystemId);

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
