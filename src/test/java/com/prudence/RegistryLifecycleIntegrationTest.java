package com.prudence;

import com.prudence.registry.adapter.in.web.dto.RegisterAiSystemRequest;
import com.prudence.registry.application.port.in.RegisterAiSystemUseCase;
import com.prudence.registry.application.port.in.ReviewAiSystemUseCase;
import com.prudence.registry.domain.RegulatoryRole;
import com.prudence.registry.domain.RiskTier;
import com.prudence.compliance.application.port.in.ComplianceControls;
import com.prudence.compliance.domain.ComplianceControlStatus;
import com.prudence.governance.application.service.GovernanceCaseService;
import com.prudence.governance.domain.GovernanceCaseType;
import com.prudence.oversight.*;
import com.prudence.shared.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:prudence-it;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "prudence.ernest.base-url=http://127.0.0.1:1",
        "prudence.opa.base-url=http://127.0.0.1:1"
})
@ActiveProfiles("test")
class RegistryLifecycleIntegrationTest {

    @Autowired
    private RegisterAiSystemUseCase register;

    @Autowired
    private ReviewAiSystemUseCase review;

    @Autowired
    private ComplianceControls compliance;

    @Autowired
    private GovernanceCaseService governanceCases;
    @Autowired private HumanAssignmentRepository assignments;

    @Test
    void registrationRequiresAssessmentThenExplicitReviewBeforeApproval() throws Exception {
        RegisterAiSystemRequest request = new RegisterAiSystemRequest(
                "Credit decision assistant", "Assist credit analysts", "Owner", "owner@bank.eu",
                RiskTier.HIGH, RegulatoryRole.DEPLOYER, "1.0.0", "a".repeat(64), false, true,
                "European Union", "Retail credit applicants", "Annex III creditworthiness use case",
                List.of("Article 9 risk management", "Article 14 human oversight"));

        var created = register.register(request.toCommand());
        assertNotNull(created);
        assertEquals("UNDER_ASSESSMENT", created.approvalStatus().name());

        var controls = compliance.forSystem(created.id().value());
        assertEquals(5, controls.size());
        assertTrue(controls.stream().allMatch(c -> c.getStatus() == ComplianceControlStatus.NOT_STARTED));
        assertThrows(DomainException.class, () -> review.submitForReview(created.id()));
        controls.forEach(control -> compliance.satisfy(created.id().value(), control.getId(), evidence()));
        assignments.save(new HumanAssignmentJpaEntity("supervisor-" + created.id().value(), created.id().value(), OversightRole.SUPERVISOR,
                "supervisor@bank.eu", true, true, "https://lms.example.eu/training", Instant.now().plusSeconds(86400).toString()));

        var submitted = review.submitForReview(created.id());
        assertEquals("PENDING_APPROVAL", submitted.approvalStatus().name());

        var firstApproval = review.approve(created.id(), "risk-reviewer");
        assertEquals("PENDING_SECOND_APPROVAL", firstApproval.approvalStatus().name());
        var approved = review.approve(created.id(), "dpo-reviewer");
        assertEquals("APPROVED", approved.approvalStatus().name());
        assertNotNull(approved.approvalExpiresAt());
    }

    @Test
    void providerHighRiskSystemGetsTheVersionedProviderProfile() {
        var created = register.register(request("Fraud prevention model", RegulatoryRole.PROVIDER).toCommand());

        var controls = compliance.forSystem(created.id().value());
        assertEquals(9, controls.size());
        assertTrue(controls.stream().allMatch(control ->
                control.getProfileId().equals("EU_AI_ACT_PROVIDER_HIGH_RISK_V1")));
    }

    @Test
    void expiredEvidenceDoesNotUnlockReview() {
        var created = register.register(request("Eligibility assessor", RegulatoryRole.DEPLOYER).toCommand());
        var controls = compliance.forSystem(created.id().value());
        for (int index = 0; index < controls.size(); index++) {
            var expiry = index == 0 ? Instant.now().minusSeconds(60).toString() : null;
            var control = controls.get(index);
            compliance.satisfy(created.id().value(), control.getId(), evidence(expiry));
        }

        assertThrows(DomainException.class, () -> review.submitForReview(created.id()));
    }

    @Test
    void dataGovernanceContractRequiresDatasetMetricsApproverAndSignedAttestation() {
        var created = register.register(request("Data contract model", RegulatoryRole.PROVIDER).toCommand());
        var dataControl = compliance.forSystem(created.id().value()).stream()
                .filter(control -> control.getControlKey().equals("DATA_GOVERNANCE")).findFirst().orElseThrow();
        var incomplete = new ComplianceControls.Evidence("https://evidence.example.eu/data.pdf", "a".repeat(64),
                "1.0.0", "governance@bank.eu", null, null, null, null, null, null);
        assertThrows(DomainException.class, () -> compliance.satisfy(created.id().value(), dataControl.getId(), incomplete));
    }

    @Test
    void governanceCaseKeepsReferencesAndAnAuditableDecisionWithoutStoringDocuments() {
        var system = register.register(request("Model change dossier", RegulatoryRole.DEPLOYER).toCommand());
        var c = governanceCases.open(system.id().value(), GovernanceCaseType.CHANGE, "Version 1.1 rollout",
                "Assess the changed model and deployment conditions.", "governance@bank.eu", "owner@bank.eu");
        assertThrows(DomainException.class, () -> governanceCases.close(c.getId(), "Approved", "reviewer@bank.eu"));
        governanceCases.addEvidence(c.getId(), "Change assessment", "https://dms.example.eu/change-11.pdf",
                "a".repeat(64), "1.1", "governance@bank.eu", "owner@bank.eu");
        governanceCases.startReview(c.getId(), "reviewer@bank.eu");
        var closed = governanceCases.close(c.getId(), "Approved with monitoring condition", "reviewer@bank.eu");
        assertEquals("CLOSED", closed.getStatus().name());
        assertEquals(1, governanceCases.evidence(c.getId()).size());
        assertEquals(4, governanceCases.timeline(c.getId()).size());
    }

    private static RegisterAiSystemRequest request(String name, RegulatoryRole role) {
        return new RegisterAiSystemRequest(
                name, "Assist credit analysts", "Owner", "owner@bank.eu",
                RiskTier.HIGH, role, "1.0.0", "a".repeat(64), false, true,
                "European Union", "Retail credit applicants", "Annex III creditworthiness use case",
                List.of("Article 9 risk management", "Article 14 human oversight"));
    }

    private static ComplianceControls.Evidence evidence() {
        return evidence(null);
    }

    private static ComplianceControls.Evidence evidence(String expiresAt) {
        return new ComplianceControls.Evidence("https://evidence.example.eu/control.pdf", "a".repeat(64),
                "1.0.0", "governance@bank.eu", "dataset-v1", "approval-rate: group-a=0.91; group-b=0.90",
                "risk-reviewer", "signed connector attestation", "ernest://receipt/example", expiresAt);
    }
}
