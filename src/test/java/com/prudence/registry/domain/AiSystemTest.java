package com.prudence.registry.domain;

import com.prudence.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

class AiSystemTest {

    private static final AiSystemId SYSTEM_ID = new AiSystemId("system-1");
    private static final AiSystem.IntendedPurpose PURPOSE = new AiSystem.IntendedPurpose("risk assessment");
    private static final AiSystem.Owner OWNER = new AiSystem.Owner("owner", "owner@example.test");

    @Test
    void registrationLeavesHighRiskSystemUnderAssessment() {
        AiSystem system = register(RiskTier.HIGH);

        assertEquals(ApprovalStatus.UNDER_ASSESSMENT, system.approvalStatus());
        assertFalse(system.isApproved());
    }

    @Test
    void registrationDoesNotAutoApproveLimitedRiskSystem() {
        AiSystem system = register(RiskTier.LIMITED);

        assertEquals(ApprovalStatus.UNDER_ASSESSMENT, system.approvalStatus());
        assertFalse(system.isApproved());
    }

    @Test
    void registrationBlocksProhibitedSystem() {
        AiSystem system = register(RiskTier.PROHIBITED);

        assertEquals(ApprovalStatus.BLOCKED, system.approvalStatus());
        assertFalse(system.isApproved());
    }

    @Test
    void reviewerCanApprovePendingSystem() {
        AiSystem system = register(RiskTier.HIGH);
        system.submitForReview();

        system.approve("risk-reviewer", 365);
        system.approve("dpo-reviewer", 365);

        assertEquals(ApprovalStatus.APPROVED, system.approvalStatus());
        assertTrue(system.isApproved());
    }

    @Test
    void rejectedSystemMustBeResubmittedBeforeApproval() {
        AiSystem system = register(RiskTier.HIGH);
        system.submitForReview();
        system.reject();

        assertThrows(DomainException.class, () -> system.approve("risk-reviewer", 365));
        system.submitForReview();
        system.approve("risk-reviewer", 365);
        system.approve("dpo-reviewer", 365);

        assertTrue(system.isApproved());
    }

    @Test
    void prohibitedSystemCannotBeApproved() {
        AiSystem system = register(RiskTier.PROHIBITED);

        assertThrows(DomainException.class, () -> system.approve("risk-reviewer", 365));
    }

    @Test
    void alreadyApprovedSystemCannotBeApprovedAgain() {
        AiSystem system = register(RiskTier.LIMITED);
        system.submitForReview();
        system.approve("risk-reviewer", 365);

        assertThrows(DomainException.class, () -> system.approve("risk-reviewer", 365));
    }

    @Test
    void reviewerCanRejectPendingSystem() {
        AiSystem system = register(RiskTier.HIGH);
        system.submitForReview();

        system.reject();

        assertEquals(ApprovalStatus.REJECTED, system.approvalStatus());
        assertFalse(system.isApproved());
    }

    @Test
    void blockedSystemCannotBeRejected() {
        AiSystem system = register(RiskTier.PROHIBITED);

        assertThrows(DomainException.class, system::reject);
    }

    @Test
    void reclassificationResetsApprovalAccordingToNewRiskTier() {
        AiSystem system = register(RiskTier.LIMITED);

        system.reclassify(RiskTier.HIGH);
        assertEquals(ApprovalStatus.UNDER_ASSESSMENT, system.approvalStatus());

        system.reclassify(RiskTier.PROHIBITED);
        assertEquals(ApprovalStatus.BLOCKED, system.approvalStatus());

        system.reclassify(RiskTier.MINIMAL);
        assertEquals(ApprovalStatus.UNDER_ASSESSMENT, system.approvalStatus());
    }

    @Test
    void suspendedSystemRequiresANewIndependentReviewBeforeItCanRunAgain() {
        AiSystem system = register(RiskTier.HIGH);
        system.submitForReview();
        system.approve("risk-reviewer", 365);
        system.approve("dpo-reviewer", 365);
        assertTrue(system.isApproved());

        system.suspend();
        assertEquals(ApprovalStatus.SUSPENDED, system.approvalStatus());
        assertFalse(system.isApproved());
        assertThrows(DomainException.class, () -> system.approve("third-reviewer", 365));

        system.submitForReview();
        assertEquals(ApprovalStatus.PENDING_APPROVAL, system.approvalStatus());
        system.approve("risk-reviewer", 365);
        system.approve("dpo-reviewer", 365);
        assertTrue(system.isApproved());
    }

    private static AiSystem register(RiskTier riskTier) {
        return AiSystem.register(SYSTEM_ID, "assistant", PURPOSE, OWNER, riskTier, classification());
    }

    private static ClassificationAssessment classification() {
        return new ClassificationAssessment(RegulatoryRole.DEPLOYER, "1.0", "a".repeat(64), false,
                true, "European Union", "test users", "Documented assessment", List.of("Article 9"), null);
    }
}
