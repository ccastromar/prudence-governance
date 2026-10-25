package cc.ccmlab.prudence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ccmlab.prudence.compliance.application.port.in.ComplianceControls;
import cc.ccmlab.prudence.oversight.HumanAssignmentJpaEntity;
import cc.ccmlab.prudence.oversight.HumanAssignmentRepository;
import cc.ccmlab.prudence.oversight.OversightRole;
import cc.ccmlab.prudence.registry.adapter.in.web.dto.RegisterAiSystemRequest;
import cc.ccmlab.prudence.registry.application.port.in.RegisterAiSystemUseCase;
import cc.ccmlab.prudence.registry.application.port.in.ReviewAiSystemUseCase;
import cc.ccmlab.prudence.registry.domain.AiSystem;
import cc.ccmlab.prudence.registry.domain.RegulatoryRole;
import cc.ccmlab.prudence.registry.domain.RiskTier;
import cc.ccmlab.prudence.shared.exception.DomainException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/// Phase 4: the registry applies the organization's rules from its governance model.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:prudence-rules-it;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "prudence.ernest.base-url=http://127.0.0.1:1",
      "prudence.opa.base-url=http://127.0.0.1:1",
      "prudence.governance.model=src/test/resources/governance/strict-model.yaml"
    })
@ActiveProfiles("test")
class GovernanceRulesIntegrationTest {

  @Autowired private RegisterAiSystemUseCase register;
  @Autowired private ReviewAiSystemUseCase review;
  @Autowired private ComplianceControls compliance;
  @Autowired private HumanAssignmentRepository assignments;

  @Test
  void accountabilitiesComeFromTheModel() {
    AiSystem system = assessed(RiskTier.HIGH, "alice");
    assign(system, OversightRole.SUPERVISOR);

    DomainException missing =
        assertThrows(DomainException.class, () -> review.submitForReview(system.id(), "alice"));
    assertTrue(missing.getMessage().contains("COMPLIANCE"), missing.getMessage());

    assign(system, OversightRole.COMPLIANCE);
    assertEquals(
        "PENDING_APPROVAL", review.submitForReview(system.id(), "alice").approvalStatus().name());
  }

  @Test
  void whoeverDeclaredASystemCannotReviewIt() {
    AiSystem system = assessed(RiskTier.HIGH, "alice");
    assign(system, OversightRole.SUPERVISOR);
    assign(system, OversightRole.COMPLIANCE);
    review.submitForReview(system.id(), "bob");

    assertThrows(DomainException.class, () -> review.approve(system.id(), "alice"));
    assertThrows(DomainException.class, () -> review.approve(system.id(), "bob"));
    assertThrows(DomainException.class, () -> review.reject(system.id(), "bob", "no"));

    review.approve(system.id(), "carol");
    assertEquals("APPROVED", review.approve(system.id(), "dave").approvalStatus().name());
  }

  @Test
  void aLimitedRiskSystemNeedsTwoDistinctApprovals() {
    AiSystem system = assessed(RiskTier.LIMITED, "alice");
    review.submitForReview(system.id(), "alice");

    assertEquals(
        "PENDING_SECOND_APPROVAL", review.approve(system.id(), "carol").approvalStatus().name());
    assertThrows(DomainException.class, () -> review.approve(system.id(), "carol"));
    assertEquals("APPROVED", review.approve(system.id(), "dave").approvalStatus().name());
  }

  private AiSystem assessed(RiskTier tier, String declaredBy) {
    AiSystem system =
        register.register(
            new RegisterAiSystemRequest(
                    "Assistant " + tier,
                    "Assist analysts",
                    "Owner",
                    "owner@bank.eu",
                    tier,
                    RegulatoryRole.DEPLOYER,
                    "1.0.0",
                    "a".repeat(64),
                    false,
                    tier == RiskTier.HIGH,
                    "European Union",
                    "Analysts",
                    "Classification justification",
                    List.of("Article 14 human oversight"))
                .toCommand(),
            declaredBy);
    compliance
        .forSystem(system.id().value())
        .forEach(
            control ->
                compliance.satisfy(
                    system.id().value(),
                    control.getId(),
                    new ComplianceControls.Evidence(
                        "https://evidence.example.eu/control.pdf",
                        "a".repeat(64),
                        "1.0.0",
                        "governance@bank.eu",
                        "dataset-v1",
                        "approval-rate: group-a=0.91; group-b=0.90",
                        "risk-reviewer",
                        "signed connector attestation",
                        "ernest://receipt/example",
                        null)));
    return system;
  }

  private void assign(AiSystem system, OversightRole role) {
    assignments.save(
        new HumanAssignmentJpaEntity(
            role + "-" + system.id().value(),
            system.id().value(),
            role,
            role.name().toLowerCase() + "@bank.eu",
            true,
            true,
            "https://lms.example.eu/training",
            Instant.now().plusSeconds(86400).toString()));
  }
}
