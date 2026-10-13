package com.prudence.evidence.adapter.out.persistence;

import com.prudence.evidence.delivery.Delivery;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/// Durable transactional outbox entry. A decision is committed locally before any network
/// call to Ernest, so temporary evidence-plane failures cannot lose governance evidence.
@Entity
@Table(name = "evidence_outbox")
public class EvidenceOutboxJpaEntity {
    @Id private String id;
    private String subject;
    private String pipeline;
    private String aiSystemId;
    private String action;
    private String policyVersion;
    private String policyHash;
    private String systemVersion;
    private String modelHash;
    private String verdict;
    @Column(length = 2000) private String reason;
    private String createdAt;
    @Embedded private Delivery delivery;

    protected EvidenceOutboxJpaEntity() {}

    public EvidenceOutboxJpaEntity(String id, String subject, String pipeline, String aiSystemId, String action,
                                   String policyVersion, String policyHash, String systemVersion, String modelHash,
                                   String verdict, String reason) {
        this.id = id; this.subject = subject; this.pipeline = pipeline; this.aiSystemId = aiSystemId; this.action = action;
        this.policyVersion = policyVersion; this.policyHash = policyHash; this.systemVersion = systemVersion;
        this.modelHash = modelHash; this.verdict = verdict; this.reason = reason;
        this.createdAt = Delivery.stamp(Instant.now());
        this.delivery = Delivery.pending();
    }

    public Delivery getDelivery() { return delivery; }
    public String getId() { return id; }
    public String getCreatedAt() { return createdAt; }
    public String getSubject() { return subject; }
    public String getPipeline() { return pipeline; }
    public String getAiSystemId() { return aiSystemId; }
    public String getAction() { return action; }
    public String getPolicyVersion() { return policyVersion; }
    public String getPolicyHash() { return policyHash; }
    public String getSystemVersion() { return systemVersion; }
    public String getModelHash() { return modelHash; }
    public String getVerdict() { return verdict; }
    public String getReason() { return reason; }
}
