package com.prudence.registry.application.port.out;

import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.AiSystemId;

import java.util.List;
import java.util.Optional;

/// Driven port: persistence of the AI-system aggregate. Implemented by an adapter.
public interface AiSystemRepository {
    AiSystem save(AiSystem system);
    Optional<AiSystem> findById(AiSystemId id);
    List<AiSystem> findAll();
}
