package com.prudence.registry.application.port.in;

import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.RiskTier;

/// Driving port: register a new AI system in the governance registry.
public interface RegisterAiSystemUseCase {
    AiSystem register(RegisterAiSystemCommand command);

    record RegisterAiSystemCommand(
            String name,
            String intendedPurpose,
            String ownerName,
            String ownerEmail,
            RiskTier riskTier) {}
}
