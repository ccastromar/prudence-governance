package com.prudence.registry.adapter.in.web.dto;

import com.prudence.registry.application.port.in.RegisterAiSystemUseCase.RegisterAiSystemCommand;
import com.prudence.registry.domain.RiskTier;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterAiSystemRequest(
        @NotBlank String name,
        @NotBlank String intendedPurpose,
        @NotBlank String ownerName,
        @Email String ownerEmail,
        @NotNull RiskTier riskTier) {

    public RegisterAiSystemCommand toCommand() {
        return new RegisterAiSystemCommand(name, intendedPurpose, ownerName, ownerEmail, riskTier);
    }
}
