package com.prudence;

import com.prudence.registry.adapter.in.web.dto.RegisterAiSystemRequest;
import com.prudence.registry.application.port.in.RegisterAiSystemUseCase;
import com.prudence.registry.application.port.in.ReviewAiSystemUseCase;
import com.prudence.registry.domain.RegulatoryRole;
import com.prudence.registry.domain.RiskTier;
import com.prudence.compliance.application.port.in.ComplianceControls;
import com.prudence.compliance.domain.ComplianceControlStatus;
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
        assertTrue(controls.stream().allMatch(c -> c.getStatus() == ComplianceControlStatus.PENDING));
        assertThrows(DomainException.class, () -> review.submitForReview(created.id()));
        controls.forEach(control -> compliance.satisfy(created.id().value(), control.getId(), evidence()));

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
                "1.0", "governance@bank.eu", expiresAt);
    }
}
