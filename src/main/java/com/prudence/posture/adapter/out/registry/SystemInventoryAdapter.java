package com.prudence.posture.adapter.out.registry;

import com.prudence.posture.application.port.out.SystemInventoryPort;
import com.prudence.registry.application.port.out.AiSystemRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/// ACL adapter: satisfies posture's inventory port by projecting the registry aggregate down
/// to the governance facts the posture view needs.
@Component
public class SystemInventoryAdapter implements SystemInventoryPort {

    private final AiSystemRepository registry;

    public SystemInventoryAdapter(AiSystemRepository registry) {
        this.registry = registry;
    }

    @Override
    public List<GovernedSystem> all() {
        return registry.findAll().stream()
                .map(s -> new GovernedSystem(s.id().value(), s.name(), s.riskTier(), s.approvalStatus(),
                        s.approvalExpiresAt(), s.reviewDue()))
                .toList();
    }
}
