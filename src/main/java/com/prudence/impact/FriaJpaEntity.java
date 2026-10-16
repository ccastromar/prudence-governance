package com.prudence.impact;

import jakarta.persistence.*;

@Entity
@Table(name = "fria_assessment")
public class FriaJpaEntity {
  @Id private String id;
  private String aiSystemId;
  private String jurisdiction;
  private String templateVersion;

  @Column(length = 4000)
  private String affectedGroups;

  @Column(length = 4000)
  private String assumptions;

  @Column(length = 4000)
  private String mitigations;

  private String dpiaUri;
  private String dpiaSha256;
  private String signedBy;
  private String signedAt;

  protected FriaJpaEntity() {}

  public FriaJpaEntity(
      String id,
      String s,
      String j,
      String t,
      String g,
      String a,
      String m,
      String d,
      String h,
      String by,
      String at) {
    this.id = id;
    aiSystemId = s;
    jurisdiction = j;
    templateVersion = t;
    affectedGroups = g;
    assumptions = a;
    mitigations = m;
    dpiaUri = d;
    dpiaSha256 = h;
    signedBy = by;
    signedAt = at;
  }

  public String getId() {
    return id;
  }

  public String getAiSystemId() {
    return aiSystemId;
  }

  public String getJurisdiction() {
    return jurisdiction;
  }

  public String getTemplateVersion() {
    return templateVersion;
  }

  public String getAffectedGroups() {
    return affectedGroups;
  }

  public String getAssumptions() {
    return assumptions;
  }

  public String getMitigations() {
    return mitigations;
  }

  public String getDpiaUri() {
    return dpiaUri;
  }

  public String getDpiaSha256() {
    return dpiaSha256;
  }

  public String getSignedBy() {
    return signedBy;
  }

  public String getSignedAt() {
    return signedAt;
  }
}
