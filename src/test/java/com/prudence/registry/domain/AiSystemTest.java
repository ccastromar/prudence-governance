package com.prudence.registry.domain;

import com.prudence.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSystemTest {

    private static final AiSystemId SYSTEM_ID = new AiSystemId("system-1");
    private static final AiSystem.IntendedPurpose PURPOSE = new AiSystem.IntendedPurpose("risk assessment");
    private static final AiSystem.Owner OWNER = new AiSystem.Owner("owner", "owner@example.test");

    @Test
    void registrationLeavesHighRiskSystemPendingApproval() {
        AiSystem system = register(RiskTier.HIGH);

        assertEquals(ApprovalStatus.PENDING_APPROVAL, system.approvalStatus());
        assertFalse(system.isApproved());
    }

    @Test
    void registrationApprovesLimitedRiskSystem() {
        AiSystem system = register(RiskTier.LIMITED);

        assertEquals(ApprovalStatus.APPROVED, system.approvalStatus());
        assertTrue(system.isApproved());
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

        system.approve();

        assertEquals(ApprovalStatus.APPROVED, system.approvalStatus());
        assertTrue(system.isApproved());
    }

    @Test
    void reviewerCanReapproveRejectedSystem() {
        AiSystem system = AiSystem.rehydrate(
                SYSTEM_ID, "assistant", PURPOSE, OWNER, RiskTier.HIGH, ApprovalStatus.REJECTED);

        system.approve();

        assertTrue(system.isApproved());
    }

    @Test
    void prohibitedSystemCannotBeApproved() {
        AiSystem system = register(RiskTier.PROHIBITED);

        assertThrows(DomainException.class, system::approve);
    }

    @Test
    void alreadyApprovedSystemCannotBeApprovedAgain() {
        AiSystem system = register(RiskTier.LIMITED);

        assertThrows(DomainException.class, system::approve);
    }

    @Test
    void reviewerCanRejectPendingSystem() {
        AiSystem system = register(RiskTier.HIGH);

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
        assertEquals(ApprovalStatus.PENDING_APPROVAL, system.approvalStatus());

        system.reclassify(RiskTier.PROHIBITED);
        assertEquals(ApprovalStatus.BLOCKED, system.approvalStatus());

        system.reclassify(RiskTier.MINIMAL);
        assertEquals(ApprovalStatus.APPROVED, system.approvalStatus());
    }

    private static AiSystem register(RiskTier riskTier) {
        return AiSystem.register(SYSTEM_ID, "assistant", PURPOSE, OWNER, riskTier);
    }
}
