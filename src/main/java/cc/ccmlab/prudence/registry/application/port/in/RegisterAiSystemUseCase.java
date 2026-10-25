package cc.ccmlab.prudence.registry.application.port.in;

import cc.ccmlab.prudence.registry.domain.AiSystem;
import cc.ccmlab.prudence.registry.domain.ClassificationAssessment;
import cc.ccmlab.prudence.registry.domain.RiskTier;

/// Driving port: register a new AI system in the governance registry.
public interface RegisterAiSystemUseCase {
  /// `declaredBy` is who registers the system (system.declare); null when unknown.
  AiSystem register(RegisterAiSystemCommand command, String declaredBy);

  record RegisterAiSystemCommand(
      String name,
      String intendedPurpose,
      String ownerName,
      String ownerEmail,
      RiskTier riskTier,
      ClassificationAssessment classification) {}
}
