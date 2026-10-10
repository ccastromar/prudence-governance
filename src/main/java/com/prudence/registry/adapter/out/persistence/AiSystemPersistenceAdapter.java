package com.prudence.registry.adapter.out.persistence;

import com.prudence.registry.application.port.out.AiSystemRepository;
import com.prudence.registry.domain.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/// Driven adapter: implements the repository port over Spring Data JPA, mapping between
/// the domain aggregate and the JPA entity.
@Component
public class AiSystemPersistenceAdapter implements AiSystemRepository {

    private final AiSystemJpaRepository jpa;

    public AiSystemPersistenceAdapter(AiSystemJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AiSystem save(AiSystem s) {
        return toDomain(jpa.save(toEntity(s)));
    }

    @Override
    public Optional<AiSystem> findById(AiSystemId id) {
        return jpa.findById(id.value()).map(this::toDomain);
    }

    @Override
    public List<AiSystem> findAll() {
        return jpa.findAll().stream().map(this::toDomain).toList();
    }

    private AiSystemJpaEntity toEntity(AiSystem s) {
        return new AiSystemJpaEntity(
                s.id().value(), s.name(), s.intendedPurpose().value(),
                s.owner().name(), s.owner().email(), s.riskTier(), s.approvalStatus());
    }

    private AiSystem toDomain(AiSystemJpaEntity e) {
        return AiSystem.rehydrate(
                new AiSystemId(e.getId()), e.getName(),
                new AiSystem.IntendedPurpose(e.getIntendedPurpose()),
                new AiSystem.Owner(e.getOwnerName(), e.getOwnerEmail()),
                e.getRiskTier(), e.getApprovalStatus());
    }
}
