package com.prudence.policy.application.port.out;

import com.prudence.registry.domain.RiskTier;

import java.util.Optional;

/// Anti-corruption boundary over the registry context: the policy context sees only the
/// governance facts it needs, not the registry's aggregate.
public interface RegisteredSystemLookup {
    Optional<SystemRiskView> find(String aiSystemId);

    record SystemRiskView(String aiSystemId, RiskTier riskTier, boolean approved) {}
}
