package com.prudence.registry.application.service;

import com.prudence.registry.application.port.in.AiSystemQueries;
import com.prudence.registry.application.port.in.RegisterAiSystemUseCase;
import com.prudence.registry.application.port.out.AiSystemRepository;
import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.AiSystemId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/// Application service implementing the registry use cases. Orchestrates the domain
/// aggregate and the persistence port; holds no business rules itself.
@Service
public class AiSystemService implements RegisterAiSystemUseCase, AiSystemQueries {

    private final AiSystemRepository repository;

    public AiSystemService(AiSystemRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public AiSystem register(RegisterAiSystemCommand command) {
        AiSystem system = AiSystem.register(
                AiSystemId.newId(),
                command.name(),
                new AiSystem.IntendedPurpose(command.intendedPurpose()),
                new AiSystem.Owner(command.ownerName(), command.ownerEmail()),
                command.riskTier());
        return repository.save(system);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiSystem> findById(AiSystemId id) {
        return repository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiSystem> findAll() {
        return repository.findAll();
    }
}
