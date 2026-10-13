package com.prudence.registry.adapter.out.persistence;

import com.prudence.registry.domain.ApprovalStatus;
import com.prudence.registry.domain.RiskTier;
import com.prudence.registry.domain.RegulatoryRole;
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

    @Enumerated(EnumType.STRING)
    private RegulatoryRole regulatoryRole;
    private String systemVersion;
    private String modelHash;
    private boolean annexI;
    private boolean annexIII;
    private String operatingGeography;
    private String affectedPopulation;
    @Column(length = 4000)
    private String classificationJustification;
    @Column(length = 4000)
    private String obligations;
    private String assessedAt;
    private String approvedAt;
    private String approvalExpiresAt;
    private String firstApprover;
    private String firstApprovedAt;
    private String renewalFirstApprover;
    private String renewalFirstApprovedAt;
    private String reviewDueNotifiedAt;

    protected AiSystemJpaEntity() {}

    public AiSystemJpaEntity(String id, String name, String intendedPurpose, String ownerName,
                             String ownerEmail, RiskTier riskTier, ApprovalStatus approvalStatus,
                             RegulatoryRole regulatoryRole, String systemVersion, String modelHash,
                             boolean annexI, boolean annexIII, String operatingGeography,
                             String affectedPopulation, String classificationJustification,
                             String obligations, String assessedAt, String approvedAt, String approvalExpiresAt,
                             String firstApprover, String firstApprovedAt,
                             String renewalFirstApprover, String renewalFirstApprovedAt, String reviewDueNotifiedAt) {
        this.id = id;
        this.name = name;
        this.intendedPurpose = intendedPurpose;
        this.ownerName = ownerName;
        this.ownerEmail = ownerEmail;
        this.riskTier = riskTier;
        this.approvalStatus = approvalStatus;
        this.regulatoryRole = regulatoryRole;
        this.systemVersion = systemVersion;
        this.modelHash = modelHash;
        this.annexI = annexI;
        this.annexIII = annexIII;
        this.operatingGeography = operatingGeography;
        this.affectedPopulation = affectedPopulation;
        this.classificationJustification = classificationJustification;
        this.obligations = obligations;
        this.assessedAt = assessedAt;
        this.approvedAt = approvedAt;
        this.approvalExpiresAt = approvalExpiresAt;
        this.firstApprover = firstApprover;
        this.firstApprovedAt = firstApprovedAt;
        this.renewalFirstApprover = renewalFirstApprover;
        this.renewalFirstApprovedAt = renewalFirstApprovedAt;
        this.reviewDueNotifiedAt = reviewDueNotifiedAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getIntendedPurpose() { return intendedPurpose; }
    public String getOwnerName() { return ownerName; }
    public String getOwnerEmail() { return ownerEmail; }
    public RiskTier getRiskTier() { return riskTier; }
    public ApprovalStatus getApprovalStatus() { return approvalStatus; }
    public RegulatoryRole getRegulatoryRole() { return regulatoryRole; }
    public String getSystemVersion() { return systemVersion; }
    public String getModelHash() { return modelHash; }
    public boolean isAnnexI() { return annexI; }
    public boolean isAnnexIII() { return annexIII; }
    public String getOperatingGeography() { return operatingGeography; }
    public String getAffectedPopulation() { return affectedPopulation; }
    public String getClassificationJustification() { return classificationJustification; }
    public String getObligations() { return obligations; }
    public String getAssessedAt() { return assessedAt; }
    public String getApprovedAt() { return approvedAt; }
    public String getApprovalExpiresAt() { return approvalExpiresAt; }
    public String getFirstApprover() { return firstApprover; }
    public String getFirstApprovedAt() { return firstApprovedAt; }
    public String getRenewalFirstApprover() { return renewalFirstApprover; }
    public String getRenewalFirstApprovedAt() { return renewalFirstApprovedAt; }
    public String getReviewDueNotifiedAt() { return reviewDueNotifiedAt; }
}
