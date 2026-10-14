package com.prudence.attestation.adapter.out.persistence;

import com.prudence.evidence.delivery.Delivery;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// JPA row for an issued attestation. The exact report JSON that was hashed is stored
/// verbatim so its content hash can be reproduced and verified later. The row is also its own
/// evidence outbox: `delivery` tracks the hash on its way to Ernest, and the seal receipt
/// (`sealed`, `blockIndex`, `sealDetail`) is written once Ernest has recorded it.
@Entity
@Table(name = "attestation")
public class AttestationJpaEntity {

  @Id private String id;
  private String generatedAt;
  private String overallPosture;
  private String contentHash;
  private boolean sealed;
  private Long blockIndex;
  private String modelId;
  private String sealDetail;

  @Column(length = 1_000_000)
  private String reportJson;

  @Embedded private Delivery delivery;

  protected AttestationJpaEntity() {}

  public AttestationJpaEntity(
      String id,
      String generatedAt,
      String overallPosture,
      String contentHash,
      String modelId,
      String sealDetail,
      String reportJson) {
    this.id = id;
    this.generatedAt = generatedAt;
    this.overallPosture = overallPosture;
    this.contentHash = contentHash;
    this.modelId = modelId;
    this.sealDetail = sealDetail;
    this.reportJson = reportJson;
    this.delivery = Delivery.pending();
  }

  /// Ernest's receipt for this report's hash.
  public void recordSeal(boolean qualified, Long blockIndex, String detail) {
    this.sealed = qualified;
    this.blockIndex = blockIndex;
    this.sealDetail = detail;
  }

  public String getId() {
    return id;
  }

  public String getGeneratedAt() {
    return generatedAt;
  }

  public String getOverallPosture() {
    return overallPosture;
  }

  public String getContentHash() {
    return contentHash;
  }

  public boolean isSealed() {
    return sealed;
  }

  public Long getBlockIndex() {
    return blockIndex;
  }

  public String getModelId() {
    return modelId;
  }

  public String getSealDetail() {
    return sealDetail;
  }

  public String getReportJson() {
    return reportJson;
  }

  public Delivery getDelivery() {
    return delivery;
  }
}
