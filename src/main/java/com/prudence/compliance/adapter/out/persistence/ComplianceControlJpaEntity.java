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
    private String accepts;
    private Integer expiresAfterDays;
    private boolean expiresOnVersionChange;
    private boolean blocksVersionChange;
    private String evidenceSource;
    @Enumerated(EnumType.STRING) private ComplianceControlStatus status;
    @Column(length = 2000) private String evidenceUri;
    private String evidenceSha256;
    private String evidenceVersion;
    private String evidenceOwner;
    private String evidenceDatasetVersion;
    @Column(length = 4000) private String evidenceMetrics;
    private String evidenceApprover;
    @Column(length = 4000) private String evidenceAttestation;
    private String ernestReceipt;
    private String satisfiedAt;
    private String expiresAt;
    private String createdAt;

    protected ComplianceControlJpaEntity() {}

    public ComplianceControlJpaEntity(String id, String aiSystemId, String profileId, String controlKey,
                                      String title, String requirement, boolean blocking, String accepts, Integer expiresAfterDays,
                                      boolean expiresOnVersionChange, boolean blocksVersionChange, String evidenceSource, String createdAt) {
        this.id = id; this.aiSystemId = aiSystemId; this.profileId = profileId; this.controlKey = controlKey;
        this.title = title; this.requirement = requirement; this.blocking = blocking;
        this.status = ComplianceControlStatus.NOT_STARTED; this.accepts=accepts; this.expiresAfterDays=expiresAfterDays;
        this.expiresOnVersionChange=expiresOnVersionChange; this.blocksVersionChange=blocksVersionChange; this.evidenceSource=evidenceSource; this.createdAt = createdAt;
    }

    public void requestEvidence() { if (status == ComplianceControlStatus.NOT_STARTED) status = ComplianceControlStatus.EVIDENCE_REQUESTED; }
    /// A deployment change made the prior attestation stale; retain its reference for audit but
    /// require a fresh one before the next approval.
    public void invalidateForChange() { if (status == ComplianceControlStatus.SATISFIED) status = ComplianceControlStatus.NOT_STARTED; }
    public void satisfy(String uri, String sha256, String version, String owner, String datasetVersion, String metrics, String approver,
                        String attestation, String receipt, String satisfiedAt, String expiresAt) {
        this.evidenceUri = uri; this.evidenceSha256 = sha256; this.evidenceVersion = version;
        this.evidenceOwner = owner; this.satisfiedAt = satisfiedAt; this.expiresAt = expiresAt;
        this.evidenceDatasetVersion=datasetVersion; this.evidenceMetrics=metrics; this.evidenceApprover=approver;
        this.evidenceAttestation=attestation; this.ernestReceipt=receipt;
        this.status = ComplianceControlStatus.SATISFIED;
    }

    public String getId() { return id; }
    public String getAiSystemId() { return aiSystemId; }
    public String getProfileId() { return profileId; }
    public String getControlKey() { return controlKey; }
    public String getTitle() { return title; }
    public String getRequirement() { return requirement; }
    public boolean isBlocking() { return blocking; }
    public String getAccepts() { return accepts; } public Integer getExpiresAfterDays() { return expiresAfterDays; }
    public boolean isExpiresOnVersionChange() { return expiresOnVersionChange; } public boolean isBlocksVersionChange() { return blocksVersionChange; } public String getEvidenceSource() { return evidenceSource; }
    public ComplianceControlStatus getStatus() { return status; }
    public String getEvidenceUri() { return evidenceUri; }
    public String getEvidenceSha256() { return evidenceSha256; }
    public String getEvidenceVersion() { return evidenceVersion; }
    public String getEvidenceOwner() { return evidenceOwner; }
    public String getEvidenceDatasetVersion() { return evidenceDatasetVersion; } public String getEvidenceMetrics() { return evidenceMetrics; }
    public String getEvidenceApprover() { return evidenceApprover; } public String getEvidenceAttestation() { return evidenceAttestation; } public String getErnestReceipt() { return ernestReceipt; }
    public String getSatisfiedAt() { return satisfiedAt; }
    public String getExpiresAt() { return expiresAt; }
    public String getCreatedAt() { return createdAt; }
}
