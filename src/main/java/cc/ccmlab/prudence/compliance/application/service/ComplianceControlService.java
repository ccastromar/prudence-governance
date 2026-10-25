package cc.ccmlab.prudence.compliance.application.service;

import cc.ccmlab.prudence.compliance.adapter.out.persistence.ComplianceControlJpaEntity;
import cc.ccmlab.prudence.compliance.adapter.out.persistence.ComplianceControlJpaRepository;
import cc.ccmlab.prudence.compliance.application.port.in.ComplianceControls;
import cc.ccmlab.prudence.compliance.domain.ComplianceControlStatus;
import cc.ccmlab.prudence.governance.GovernanceModel;
import cc.ccmlab.prudence.impact.FriaRepository;
import cc.ccmlab.prudence.impact.ImpactRequirementRepository;
import cc.ccmlab.prudence.impact.TransparencyNoticeRepository;
import cc.ccmlab.prudence.oversight.HumanAssignmentRepository;
import cc.ccmlab.prudence.oversight.OversightRole;
import cc.ccmlab.prudence.registry.domain.AiSystem;
import cc.ccmlab.prudence.registry.domain.RiskTier;
import cc.ccmlab.prudence.shared.exception.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComplianceControlService implements ComplianceControls {
  private final ComplianceControlJpaRepository controls;
  private final ComplianceProfileCatalog catalog;
  private final HumanAssignmentRepository assignments;
  private final ImpactRequirementRepository impactRequirements;
  private final FriaRepository frias;
  private final TransparencyNoticeRepository transparencyNotices;
  private final GovernanceModel governance;

  public ComplianceControlService(
      ComplianceControlJpaRepository controls,
      ComplianceProfileCatalog catalog,
      HumanAssignmentRepository assignments,
      ImpactRequirementRepository impactRequirements,
      FriaRepository frias,
      TransparencyNoticeRepository transparencyNotices,
      GovernanceModel governance) {
    this.controls = controls;
    this.catalog = catalog;
    this.assignments = assignments;
    this.impactRequirements = impactRequirements;
    this.frias = frias;
    this.transparencyNotices = transparencyNotices;
    this.governance = governance;
  }

  /// The accountabilities the governance model asks of the system's tier (rules.accountable),
  /// each held by a person whose training is current. A SUPERVISOR must also be able to
  /// override or stop the system (AI Act art. 14); the legal floor always asks one of HIGH.
  private void requireAccountable(AiSystem system) {
    var assigned = assignments.findByAiSystemId(system.id().value());
    Instant now = Instant.now();
    List<String> missing = new ArrayList<>();
    for (String role : governance.accountable(system.riskTier().name())) {
      OversightRole required = OversightRole.valueOf(role);
      boolean held =
          assigned.stream()
              .anyMatch(
                  a ->
                      a.getRole() == required
                          && a.getTrainingExpiresAt() != null
                          && Instant.parse(a.getTrainingExpiresAt()).isAfter(now)
                          && (required != OversightRole.SUPERVISOR
                              || a.isOverrideAuthority()
                              || a.isStopAuthority()));
      if (!held) missing.add(role);
    }
    if (missing.contains("SUPERVISOR") && system.riskTier() == RiskTier.HIGH) {
      throw new DomainException(
          "a high-risk system requires a current supervisor with override or stop authority before"
              + " review");
    }
    if (!missing.isEmpty()) {
      throw new DomainException(
          "accountabilities required before review (governance model): "
              + String.join(", ", missing));
    }
  }

  @Override
  @Transactional
  public void initialize(AiSystem system) {
    for (ComplianceProfileCatalog.Profile profile : catalog.applicableTo(system)) {
      if (controls.existsByAiSystemIdAndProfileId(system.id().value(), profile.id())) continue;
      String now = Instant.now().toString();
      controls.saveAll(
          profile.controls().stream()
              .map(
                  definition ->
                      new ComplianceControlJpaEntity(
                          UUID.randomUUID().toString(),
                          system.id().value(),
                          profile.id(),
                          definition.key(),
                          definition.title(),
                          definition.requirement(),
                          definition.blocking(),
                          definition.accepts(),
                          definition.expiresAfterDays(),
                          definition.expiresOnVersionChange(),
                          definition.blocksVersionChange(),
                          definition.source(),
                          now))
              .toList());
    }
  }

  @Override
  @Transactional
  public List<ComplianceControlJpaEntity> initializeAndList(AiSystem system) {
    initialize(system);
    return forSystem(system.id().value());
  }

  @Override
  @Transactional
  public void requireReadyForReview(AiSystem system) {
    initialize(system); // also upgrades legacy systems the first time they enter review.
    List<String> missing =
        forSystem(system.id().value()).stream()
            .filter(ComplianceControlJpaEntity::isBlocking)
            .filter(control -> !satisfiedAndCurrent(control, system))
            .map(control -> control.getControlKey() + " (" + control.getTitle() + ")")
            .toList();
    if (!missing.isEmpty()) {
      throw new DomainException(
          "compliance controls required before review: " + String.join(", ", missing));
    }
    requireAccountable(system);
    var requirement = impactRequirements.findById(system.id().value()).orElse(null);
    if (requirement != null
        && requirement.isFriaRequired()
        && !frias.existsByAiSystemId(system.id().value()))
      throw new DomainException("FRIA is required for this system before review");
    if (requirement != null
        && requirement.isTransparencyRequired()
        && transparencyNotices.findByAiSystemIdOrderByDeployedAtDesc(system.id().value()).isEmpty())
      throw new DomainException(
          "transparency notice deployment evidence is required before review");
  }

  @Override
  @Transactional(readOnly = true)
  public List<ComplianceControlJpaEntity> forSystem(String aiSystemId) {
    return controls.findByAiSystemIdOrderByProfileIdAscControlKeyAsc(aiSystemId);
  }

  @Override
  @Transactional
  public ComplianceControlJpaEntity satisfy(
      String aiSystemId, String controlId, Evidence evidence) {
    ComplianceControlJpaEntity control =
        controls
            .findByIdAndAiSystemId(controlId, aiSystemId)
            .orElseThrow(() -> new DomainException("compliance control not found: " + controlId));
    validateEvidence(evidence);
    if (control.getAccepts().contains("dataset-version") && blank(evidence.datasetVersion()))
      throw new DomainException("dataset version is required by this evidence contract");
    if (control.getAccepts().contains("metrics-by-group") && blank(evidence.metricsByGroup()))
      throw new DomainException("metrics by group are required by this evidence contract");
    if (control.getAccepts().contains("approver") && blank(evidence.approver()))
      throw new DomainException("approver is required by this evidence contract");
    if (control.getAccepts().contains("signed-attestation") && blank(evidence.signedAttestation()))
      throw new DomainException("signed attestation is required by this evidence contract");
    String expiresAt = effectiveExpiry(evidence.expiresAt(), control.getExpiresAfterDays());
    control.satisfy(
        evidence.uri(),
        evidence.sha256(),
        evidence.version(),
        evidence.owner(),
        evidence.datasetVersion(),
        evidence.metricsByGroup(),
        evidence.approver(),
        evidence.signedAttestation(),
        evidence.ernestReceipt(),
        Instant.now().toString(),
        expiresAt);
    return controls.save(control);
  }

  @Transactional
  public int invalidateAffectedByChange(String aiSystemId, Set<String> fields) {
    Set<String> keys = new java.util.HashSet<>();
    if (fields.contains("DATA_OR_EMBEDDINGS")) keys.add("DATA_GOVERNANCE");
    if (fields.contains("PROMPT_OR_TOOLS") || fields.contains("INTEGRATION_ENVIRONMENT"))
      keys.add("HUMAN_OVERSIGHT");
    if (fields.contains("GPAI_PROVIDER_MODEL") || fields.contains("MODEL_VERSION"))
      keys.add("ROBUSTNESS_SECURITY");
    if (fields.contains("SECURITY_CONFIGURATION")) keys.add("ROBUSTNESS_SECURITY");
    var affected =
        controls.findByAiSystemIdOrderByProfileIdAscControlKeyAsc(aiSystemId).stream()
            .filter(c -> keys.contains(c.getControlKey()))
            .toList();
    affected.forEach(ComplianceControlJpaEntity::invalidateForChange);
    controls.saveAll(affected);
    return affected.size();
  }

  private static boolean satisfiedAndCurrent(ComplianceControlJpaEntity control, AiSystem system) {
    if (control.getStatus() != ComplianceControlStatus.SATISFIED) return false;
    if (control.isExpiresOnVersionChange()
        && !system.classification().systemVersion().equals(control.getEvidenceVersion()))
      return false;
    return control.getExpiresAt() == null
        || control.getExpiresAt().isBlank()
        || Instant.parse(control.getExpiresAt()).isAfter(Instant.now());
  }

  private static String effectiveExpiry(String requested, Integer days) {
    if (days == null) return blank(requested) ? null : requested;
    Instant maximumExpiry = Instant.now().plusSeconds(days * 86400L);
    if (blank(requested)) return maximumExpiry.toString();
    Instant requestedExpiry = Instant.parse(requested);
    return (requestedExpiry.isAfter(maximumExpiry) ? maximumExpiry : requestedExpiry).toString();
  }

  private static void validateEvidence(Evidence evidence) {
    if (evidence == null
        || blank(evidence.uri())
        || blank(evidence.sha256())
        || blank(evidence.version())
        || blank(evidence.owner())) {
      throw new DomainException("evidence uri, sha256, version and owner are required");
    }
    String hash =
        evidence.sha256().startsWith("sha256:")
            ? evidence.sha256().substring(7)
            : evidence.sha256();
    if (!hash.matches("[a-fA-F0-9]{64}"))
      throw new DomainException("evidence sha256 must be a SHA-256 hex digest");
    if (!blank(evidence.expiresAt())) {
      try {
        Instant.parse(evidence.expiresAt());
      } catch (Exception e) {
        throw new DomainException("evidence expiresAt must be an ISO-8601 instant");
      }
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
