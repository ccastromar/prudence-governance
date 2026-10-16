package com.prudence.registry.adapter.in.web.dto;

import com.prudence.registry.application.port.in.RegisterAiSystemUseCase.RegisterAiSystemCommand;
import com.prudence.registry.domain.ClassificationAssessment;
import com.prudence.registry.domain.RegulatoryRole;
import com.prudence.registry.domain.RiskTier;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record RegisterAiSystemRequest(
    @NotBlank String name,
    @NotBlank String intendedPurpose,
    @NotBlank String ownerName,
    @Email String ownerEmail,
    @NotNull RiskTier riskTier,
    @NotNull RegulatoryRole regulatoryRole,
    @NotBlank String systemVersion,
    @NotBlank String modelHash,
    boolean annexI,
    boolean annexIII,
    @NotBlank String operatingGeography,
    @NotBlank String affectedPopulation,
    @NotBlank String classificationJustification,
    @NotNull List<@NotBlank String> obligations) {

  public RegisterAiSystemCommand toCommand() {
    return new RegisterAiSystemCommand(
        name,
        intendedPurpose,
        ownerName,
        ownerEmail,
        riskTier,
        new ClassificationAssessment(
            regulatoryRole,
            systemVersion,
            modelHash,
            annexI,
            annexIII,
            operatingGeography,
            affectedPopulation,
            classificationJustification,
            obligations,
            null));
  }
}
