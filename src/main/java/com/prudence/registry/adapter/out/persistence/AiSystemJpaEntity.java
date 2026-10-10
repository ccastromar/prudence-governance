package com.prudence.registry.adapter.out.persistence;

import com.prudence.registry.domain.ApprovalStatus;
import com.prudence.registry.domain.RiskTier;
import jakarta.persistence.*;

/// JPA mapping of the AI-system aggregate. Kept separate from the domain model so the
/// domain stays persistence-ignorant (hexagonal boundary).
@Entity
@Table(name = "ai_system")
public class AiSystemJpaEntity {

    @Id
    private String id;
    private String name;
    private String intendedPurpose;
    private String ownerName;
    private String ownerEmail;

    @Enumerated(EnumType.STRING)
    private RiskTier riskTier;

    @Enumerated(EnumType.STRING)
    private ApprovalStatus approvalStatus;

    protected AiSystemJpaEntity() {}

    public AiSystemJpaEntity(String id, String name, String intendedPurpose, String ownerName,
                             String ownerEmail, RiskTier riskTier, ApprovalStatus approvalStatus) {
        this.id = id;
        this.name = name;
        this.intendedPurpose = intendedPurpose;
        this.ownerName = ownerName;
        this.ownerEmail = ownerEmail;
        this.riskTier = riskTier;
        this.approvalStatus = approvalStatus;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getIntendedPurpose() { return intendedPurpose; }
    public String getOwnerName() { return ownerName; }
    public String getOwnerEmail() { return ownerEmail; }
    public RiskTier getRiskTier() { return riskTier; }
    public ApprovalStatus getApprovalStatus() { return approvalStatus; }
}
