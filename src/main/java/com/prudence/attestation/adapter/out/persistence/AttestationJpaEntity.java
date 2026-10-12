package com.prudence.attestation.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// JPA row for an issued attestation. The exact report JSON that was hashed is stored
/// verbatim so its content hash can be reproduced and verified later.
@Entity
@Table(name = "attestation")
public class AttestationJpaEntity {

    @Id
    private String id;
    private String generatedAt;
    private String overallPosture;
    private String contentHash;
    private boolean sealed;
    private Long blockIndex;
    private String modelId;
    private String sealDetail;

    @Column(length = 1_000_000)
    private String reportJson;

    protected AttestationJpaEntity() {}

    public AttestationJpaEntity(String id, String generatedAt, String overallPosture, String contentHash,
                                boolean sealed, Long blockIndex, String modelId, String sealDetail, String reportJson) {
        this.id = id;
        this.generatedAt = generatedAt;
        this.overallPosture = overallPosture;
        this.contentHash = contentHash;
        this.sealed = sealed;
        this.blockIndex = blockIndex;
        this.modelId = modelId;
        this.sealDetail = sealDetail;
        this.reportJson = reportJson;
    }

    public String getId() { return id; }
    public String getGeneratedAt() { return generatedAt; }
    public String getOverallPosture() { return overallPosture; }
    public String getContentHash() { return contentHash; }
    public boolean isSealed() { return sealed; }
    public Long getBlockIndex() { return blockIndex; }
    public String getModelId() { return modelId; }
    public String getSealDetail() { return sealDetail; }
    public String getReportJson() { return reportJson; }
}
