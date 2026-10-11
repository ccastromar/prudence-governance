package com.prudence.registry.application.service;

import com.prudence.registry.application.port.in.AiSystemQueries;
import com.prudence.registry.application.port.in.RegisterAiSystemUseCase;
import com.prudence.registry.application.port.in.ReviewAiSystemUseCase;
import com.prudence.registry.application.port.out.AiSystemRepository;
import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.AiSystemId;
import com.prudence.registry.domain.AiSystemNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/// Application service implementing the registry use cases. Orchestrates the domain
/// aggregate and the persistence port; holds no business rules itself.
@Service
public class AiSystemService implements RegisterAiSystemUseCase, AiSystemQueries, ReviewAiSystemUseCase {

    private static final Logger log = LoggerFactory.getLogger(AiSystemService.class);

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

    @Override
    @Transactional
    public AiSystem approve(AiSystemId id, String reviewer) {
        AiSystem system = require(id);
        system.approve();
        // The approval is itself a governance act — it belongs in the tamper-evident record.
        // Scaffold logs the intent; sealing it via Ernest is wired on the evidence path.
        log.info("governance review: APPROVE system={} by={}", id.value(), reviewer);
        return repository.save(system);
    }

    @Override
    @Transactional
    public AiSystem reject(AiSystemId id, String reviewer, String reason) {
        AiSystem system = require(id);
        system.reject();
        log.info("governance review: REJECT system={} by={} reason={}", id.value(), reviewer, reason);
        return repository.save(system);
    }

    private AiSystem require(AiSystemId id) {
        return repository.findById(id).orElseThrow(() -> new AiSystemNotFoundException(id));
    }
}
