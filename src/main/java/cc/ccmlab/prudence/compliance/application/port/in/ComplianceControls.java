package cc.ccmlab.prudence.compliance.application.port.in;

import cc.ccmlab.prudence.compliance.adapter.out.persistence.ComplianceControlJpaEntity;
import cc.ccmlab.prudence.registry.domain.AiSystem;
import java.util.List;

/// Governance-facing contract for the evidence controls derived from an AI system's profile.
public interface ComplianceControls {
  void initialize(AiSystem system);

  List<ComplianceControlJpaEntity> initializeAndList(AiSystem system);

  void requireReadyForReview(AiSystem system);

  List<ComplianceControlJpaEntity> forSystem(String aiSystemId);

  ComplianceControlJpaEntity satisfy(String aiSystemId, String controlId, Evidence evidence);

  record Evidence(
      String uri,
      String sha256,
      String version,
      String owner,
      String datasetVersion,
      String metricsByGroup,
      String approver,
      String signedAttestation,
      String ernestReceipt,
      String expiresAt) {}
}
