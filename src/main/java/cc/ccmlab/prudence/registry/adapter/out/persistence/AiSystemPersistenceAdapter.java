package cc.ccmlab.prudence.registry.adapter.out.persistence;

import cc.ccmlab.prudence.registry.application.port.out.AiSystemRepository;
import cc.ccmlab.prudence.registry.domain.*;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/// Driven adapter: implements the repository port over Spring Data JPA, mapping between
/// the domain aggregate and the JPA entity.
@Component
public class AiSystemPersistenceAdapter implements AiSystemRepository {

  private final AiSystemJpaRepository jpa;

  public AiSystemPersistenceAdapter(AiSystemJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public AiSystem save(AiSystem s) {
    return toDomain(jpa.save(toEntity(s)));
  }

  @Override
  public Optional<AiSystem> findById(AiSystemId id) {
    return jpa.findById(id.value()).map(this::toDomain);
  }

  @Override
  public Optional<AiSystem> findByIdForUpdate(AiSystemId id) {
    return jpa.findLockedById(id.value()).map(this::toDomain);
  }

  @Override
  public List<AiSystemId> findIdsByStatus(ApprovalStatus status) {
    return jpa.findIdsByApprovalStatus(status).stream().map(AiSystemId::new).toList();
  }

  @Override
  public List<AiSystem> findAll() {
    return jpa.findAll().stream().map(this::toDomain).toList();
  }

  private AiSystemJpaEntity toEntity(AiSystem s) {
    ClassificationAssessment c = s.classification();
    return new AiSystemJpaEntity(
        s.id().value(),
        s.name(),
        s.intendedPurpose().value(),
        s.owner().name(),
        s.owner().email(),
        s.riskTier(),
        s.approvalStatus(),
        c.regulatoryRole(),
        c.systemVersion(),
        c.modelHash(),
        c.annexI(),
        c.annexIII(),
        c.operatingGeography(),
        c.affectedPopulation(),
        c.justification(),
        String.join("\n", c.obligations()),
        c.assessedAt(),
        s.approvedAt(),
        s.approvalExpiresAt(),
        s.firstApprover(),
        s.firstApprovedAt(),
        s.renewalFirstApprover(),
        s.renewalFirstApprovedAt(),
        s.reviewDueNotifiedAt());
  }

  private AiSystem toDomain(AiSystemJpaEntity e) {
    boolean legacy = e.getRegulatoryRole() == null;
    ClassificationAssessment classification =
        legacy
            ? legacyClassification(e)
            : new ClassificationAssessment(
                e.getRegulatoryRole(),
                e.getSystemVersion(),
                e.getModelHash(),
                e.isAnnexI(),
                e.isAnnexIII(),
                e.getOperatingGeography(),
                e.getAffectedPopulation(),
                e.getClassificationJustification(),
                split(e.getObligations()),
                e.getAssessedAt());
    // Records created before the assessment dossier existed are never trusted as approved.
    // They remain visible and must be reassessed, rather than disappearing or continuing
    // to authorise an execution based on incomplete regulatory facts.
    ApprovalStatus status =
        legacy && e.getRiskTier() != RiskTier.PROHIBITED
            ? ApprovalStatus.UNDER_ASSESSMENT
            : e.getApprovalStatus();
    return AiSystem.rehydrate(
        new AiSystemId(e.getId()),
        e.getName(),
        new AiSystem.IntendedPurpose(e.getIntendedPurpose()),
        new AiSystem.Owner(e.getOwnerName(), e.getOwnerEmail()),
        e.getRiskTier(),
        status,
        classification,
        e.getApprovedAt(),
        e.getApprovalExpiresAt(),
        e.getFirstApprover(),
        e.getFirstApprovedAt(),
        e.getRenewalFirstApprover(),
        e.getRenewalFirstApprovedAt(),
        e.getReviewDueNotifiedAt());
  }

  private static ClassificationAssessment legacyClassification(AiSystemJpaEntity e) {
    return new ClassificationAssessment(
        RegulatoryRole.DEPLOYER,
        "legacy-unassessed",
        "unknown",
        false,
        e.getRiskTier().isAnnexIII(),
        "Unknown — reassessment required",
        "Unknown — reassessment required",
        "Legacy record has no classification dossier.",
        List.of("Reassess classification before review"),
        "");
  }

  private static List<String> split(String obligations) {
    return obligations == null || obligations.isBlank()
        ? List.of("legacy-classification")
        : List.of(obligations.split("\\n"));
  }
}
