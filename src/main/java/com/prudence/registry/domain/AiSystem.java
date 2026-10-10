package com.prudence.registry.domain;

import com.prudence.shared.exception.DomainException;

/// Aggregate root of the governance registry: an AI system/use case with its risk tier,
/// owner and approval state. Pure domain — no framework or persistence concerns here.
public final class AiSystem {

    public record Owner(String name, String email) {
        public Owner {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("owner name required");
        }
    }

    public record IntendedPurpose(String value) {
        public IntendedPurpose {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("intended purpose required");
        }
    }

    private final AiSystemId id;
    private final String name;
    private final IntendedPurpose intendedPurpose;
    private final Owner owner;
    private RiskTier riskTier;
    private ApprovalStatus approvalStatus;

    private AiSystem(AiSystemId id, String name, IntendedPurpose intendedPurpose, Owner owner,
                     RiskTier riskTier, ApprovalStatus approvalStatus) {
        this.id = id;
        this.name = name;
        this.intendedPurpose = intendedPurpose;
        this.owner = owner;
        this.riskTier = riskTier;
        this.approvalStatus = approvalStatus;
    }

    /// Factory: register a new system. Initial approval state follows the risk tier —
    /// prohibited is blocked outright, high-risk awaits approval, the rest is cleared.
    public static AiSystem register(AiSystemId id, String name, IntendedPurpose purpose, Owner owner, RiskTier riskTier) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        ApprovalStatus initial = switch (riskTier) {
            case PROHIBITED -> ApprovalStatus.BLOCKED;
            case HIGH -> ApprovalStatus.PENDING_APPROVAL;
            case LIMITED, MINIMAL -> ApprovalStatus.APPROVED;
        };
        return new AiSystem(id, name, purpose, owner, riskTier, initial);
    }

    /// Reconstitute from persistence (no invariants re-applied).
    public static AiSystem rehydrate(AiSystemId id, String name, IntendedPurpose purpose, Owner owner,
                                     RiskTier riskTier, ApprovalStatus approvalStatus) {
        return new AiSystem(id, name, purpose, owner, riskTier, approvalStatus);
    }

    /// Reclassify risk; prohibited systems become blocked, otherwise approval is reset.
    public void reclassify(RiskTier newTier) {
        this.riskTier = newTier;
        this.approvalStatus = switch (newTier) {
            case PROHIBITED -> ApprovalStatus.BLOCKED;
            case HIGH -> ApprovalStatus.PENDING_APPROVAL;
            case LIMITED, MINIMAL -> ApprovalStatus.APPROVED;
        };
    }

    public void approve() {
        if (riskTier == RiskTier.PROHIBITED) {
            throw new DomainException("a prohibited AI system cannot be approved");
        }
        this.approvalStatus = ApprovalStatus.APPROVED;
    }

    public boolean isApproved() {
        return approvalStatus == ApprovalStatus.APPROVED;
    }

    public AiSystemId id() { return id; }
    public String name() { return name; }
    public IntendedPurpose intendedPurpose() { return intendedPurpose; }
    public Owner owner() { return owner; }
    public RiskTier riskTier() { return riskTier; }
    public ApprovalStatus approvalStatus() { return approvalStatus; }
}
