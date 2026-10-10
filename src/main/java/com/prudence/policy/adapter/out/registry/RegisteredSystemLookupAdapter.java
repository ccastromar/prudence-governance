package com.prudence.policy.adapter.out.registry;

import com.prudence.policy.application.port.out.RegisteredSystemLookup;
import com.prudence.registry.application.port.out.AiSystemRepository;
import com.prudence.registry.domain.AiSystemId;
import org.springframework.stereotype.Component;

import java.util.Optional;

/// ACL adapter: satisfies the policy context's lookup port by delegating to the registry
/// repository and projecting only the governance facts the PDP needs.
@Component
public class RegisteredSystemLookupAdapter implements RegisteredSystemLookup {

    private final AiSystemRepository registry;

    public RegisteredSystemLookupAdapter(AiSystemRepository registry) {
        this.registry = registry;
    }

    @Override
    public Optional<SystemRiskView> find(String aiSystemId) {
        return registry.findById(new AiSystemId(aiSystemId))
                .map(s -> new SystemRiskView(s.id().value(), s.riskTier(), s.isApproved()));
    }
}
