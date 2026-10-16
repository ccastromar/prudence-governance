package com.prudence.governance.adapter.out.persistence;

import jakarta.persistence.*;

/// Metadata and a digest only; the source-of-record document is never uploaded to Prudence.
@Entity
@Table(name = "governance_case_evidence")
public class GovernanceEvidenceJpaEntity {
  @Id private String id;
  private String caseId;

  @Column(length = 500)
  private String label;

  @Column(length = 2000)
  private String uri;

  private String sha256;
  private String version;
  private String owner;
  private String addedBy;
  private String addedAt;

  protected GovernanceEvidenceJpaEntity() {}

  public GovernanceEvidenceJpaEntity(
      String id,
      String caseId,
      String label,
      String uri,
      String sha256,
      String version,
      String owner,
      String addedBy,
      String addedAt) {
    this.id = id;
    this.caseId = caseId;
    this.label = label;
    this.uri = uri;
    this.sha256 = sha256;
    this.version = version;
    this.owner = owner;
    this.addedBy = addedBy;
    this.addedAt = addedAt;
  }

  public String getId() {
    return id;
  }

  public String getCaseId() {
    return caseId;
  }

  public String getLabel() {
    return label;
  }

  public String getUri() {
    return uri;
  }

  public String getSha256() {
    return sha256;
  }

  public String getVersion() {
    return version;
  }

  public String getOwner() {
    return owner;
  }

  public String getAddedBy() {
    return addedBy;
  }

  public String getAddedAt() {
    return addedAt;
  }
}
