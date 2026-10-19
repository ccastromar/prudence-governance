package cc.ccmlab.prudence.registry.application.port.in;

import cc.ccmlab.prudence.registry.domain.AiSystem;
import cc.ccmlab.prudence.registry.domain.ClassificationAssessment;
import cc.ccmlab.prudence.registry.domain.RiskTier;

/// Driving port: register a new AI system in the governance registry.
public interface RegisterAiSystemUseCase {
  AiSystem register(RegisterAiSystemCommand command);

  record RegisterAiSystemCommand(
      String name,
      String intendedPurpose,
      String ownerName,
      String ownerEmail,
      RiskTier riskTier,
      ClassificationAssessment classification) {}
}
