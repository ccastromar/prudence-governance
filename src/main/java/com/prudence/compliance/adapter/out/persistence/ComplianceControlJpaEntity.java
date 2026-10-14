package com.prudence.compliance.adapter.out.persistence;

import com.prudence.compliance.domain.ComplianceControlStatus;
import jakarta.persistence.*;

@Entity
@Table(name = "compliance_control", uniqueConstraints = @UniqueConstraint(columnNames = {"aiSystemId", "profileId", "controlKey"}))
public class ComplianceControlJpaEntity {
    @Id private String id;
    private String aiSystemId;
    private String profileId;
    private String controlKey;
    private String title;
    @Column(length = 4000) private String requirement;
    private boolean blocking;
    @Enumerated(EnumType.STRING) private ComplianceControlStatus status;
    @Column(length = 2000) private String evidenceUri;
    private String evidenceSha256;
    private String evidenceVersion;
    private String evidenceOwner;
    private String satisfiedAt;
    private String expiresAt;
    private String createdAt;

    protected ComplianceControlJpaEntity() {}

    public ComplianceControlJpaEntity(String id, String aiSystemId, String profileId, String controlKey,
                                      String title, String requirement, boolean blocking, String createdAt) {
        this.id = id; this.aiSystemId = aiSystemId; this.profileId = profileId; this.controlKey = controlKey;
        this.title = title; this.requirement = requirement; this.blocking = blocking;
        this.status = ComplianceControlStatus.PENDING; this.createdAt = createdAt;
    }

    public void satisfy(String uri, String sha256, String version, String owner, String satisfiedAt, String expiresAt) {
        this.evidenceUri = uri; this.evidenceSha256 = sha256; this.evidenceVersion = version;
        this.evidenceOwner = owner; this.satisfiedAt = satisfiedAt; this.expiresAt = expiresAt;
        this.status = ComplianceControlStatus.SATISFIED;
    }

    public String getId() { return id; }
    public String getAiSystemId() { return aiSystemId; }
    public String getProfileId() { return profileId; }
    public String getControlKey() { return controlKey; }
    public String getTitle() { return title; }
    public String getRequirement() { return requirement; }
    public boolean isBlocking() { return blocking; }
    public ComplianceControlStatus getStatus() { return status; }
    public String getEvidenceUri() { return evidenceUri; }
    public String getEvidenceSha256() { return evidenceSha256; }
    public String getEvidenceVersion() { return evidenceVersion; }
    public String getEvidenceOwner() { return evidenceOwner; }
    public String getSatisfiedAt() { return satisfiedAt; }
    public String getExpiresAt() { return expiresAt; }
    public String getCreatedAt() { return createdAt; }
}
