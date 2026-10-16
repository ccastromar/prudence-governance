package com.prudence.change;

import jakarta.persistence.*;

@Entity
@Table(name = "change_assessment")
public class ChangeAssessmentJpaEntity {
  @Id private String id;
  private String aiSystemId;
  private String proposedVersion;

  @Column(length = 4000)
  private String changedFields;

  @Enumerated(EnumType.STRING)
  private ChangeOutcome outcome;

  @Column(length = 4000)
  private String rationale;

  private String evidenceUri;
  private String evidenceSha256;
  private String assessedAt;
  private String assessedBy;

  protected ChangeAssessmentJpaEntity() {}

  public ChangeAssessmentJpaEntity(
      String id,
      String system,
      String version,
      String fields,
      ChangeOutcome outcome,
      String rationale,
      String uri,
      String hash,
      String at,
      String by) {
    this.id = id;
    aiSystemId = system;
    proposedVersion = version;
    changedFields = fields;
    this.outcome = outcome;
    this.rationale = rationale;
    evidenceUri = uri;
    evidenceSha256 = hash;
    assessedAt = at;
    assessedBy = by;
  }

  public String getId() {
    return id;
  }

  public String getAiSystemId() {
    return aiSystemId;
  }

  public String getProposedVersion() {
    return proposedVersion;
  }

  public String getChangedFields() {
    return changedFields;
  }

  public ChangeOutcome getOutcome() {
    return outcome;
  }

  public String getRationale() {
    return rationale;
  }

  public String getEvidenceUri() {
    return evidenceUri;
  }

  public String getEvidenceSha256() {
    return evidenceSha256;
  }

  public String getAssessedAt() {
    return assessedAt;
  }

  public String getAssessedBy() {
    return assessedBy;
  }
}
