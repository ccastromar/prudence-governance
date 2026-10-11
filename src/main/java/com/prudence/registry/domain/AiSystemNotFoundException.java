package com.prudence.registry.domain;

import com.prudence.shared.exception.DomainException;

/// Raised when a review or lookup targets an AI system that is not in the registry.
public class AiSystemNotFoundException extends DomainException {
    public AiSystemNotFoundException(AiSystemId id) {
        super("AI system not found: " + id.value());
    }
}
