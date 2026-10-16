package com.prudence.impact;

import jakarta.persistence.*;

@Entity
@Table(name = "transparency_notice")
public class TransparencyNoticeJpaEntity {
  @Id private String id;
  private String aiSystemId;

  @Enumerated(EnumType.STRING)
  private TransparencyKind kind;

  private String audience;
  private String noticeVersion;

  @Column(length = 2000)
  private String deploymentEvidence;

  private String deployedBy;
  private String deployedAt;

  protected TransparencyNoticeJpaEntity() {}

  public TransparencyNoticeJpaEntity(
      String id,
      String system,
      TransparencyKind kind,
      String audience,
      String version,
      String evidence,
      String by,
      String at) {
    this.id = id;
    aiSystemId = system;
    this.kind = kind;
    this.audience = audience;
    noticeVersion = version;
    deploymentEvidence = evidence;
    deployedBy = by;
    deployedAt = at;
  }

  public String getId() {
    return id;
  }

  public String getAiSystemId() {
    return aiSystemId;
  }

  public TransparencyKind getKind() {
    return kind;
  }

  public String getAudience() {
    return audience;
  }

  public String getNoticeVersion() {
    return noticeVersion;
  }

  public String getDeploymentEvidence() {
    return deploymentEvidence;
  }

  public String getDeployedBy() {
    return deployedBy;
  }

  public String getDeployedAt() {
    return deployedAt;
  }
}
