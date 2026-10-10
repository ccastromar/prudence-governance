package com.prudence.registry.application.port.in;

import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.AiSystemId;

import java.util.List;
import java.util.Optional;

/// Driving port: read side of the registry.
public interface AiSystemQueries {
    Optional<AiSystem> findById(AiSystemId id);
    List<AiSystem> findAll();
}
