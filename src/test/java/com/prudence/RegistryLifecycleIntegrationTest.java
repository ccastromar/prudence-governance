package com.prudence;

import com.prudence.registry.adapter.in.web.dto.RegisterAiSystemRequest;
import com.prudence.registry.application.port.in.RegisterAiSystemUseCase;
import com.prudence.registry.application.port.in.ReviewAiSystemUseCase;
import com.prudence.registry.domain.RegulatoryRole;
import com.prudence.registry.domain.RiskTier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

        var submitted = review.submitForReview(created.id());
        assertEquals("PENDING_APPROVAL", submitted.approvalStatus().name());

        var firstApproval = review.approve(created.id(), "risk-reviewer");
        assertEquals("PENDING_SECOND_APPROVAL", firstApproval.approvalStatus().name());
        var approved = review.approve(created.id(), "dpo-reviewer");
        assertEquals("APPROVED", approved.approvalStatus().name());
        assertNotNull(approved.approvalExpiresAt());
    }
}
