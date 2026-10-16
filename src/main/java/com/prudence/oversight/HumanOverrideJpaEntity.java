package com.prudence.oversight;

import jakarta.persistence.*;

@Entity
@Table(name = "human_override")
public class HumanOverrideJpaEntity {
  @Id private String id;
  private String aiSystemId;
  private String aosReference;
  private String supervisor;

  @Column(length = 4000)
  private String reason;

  @Column(length = 4000)
  private String outcome;

  private String occurredAt;
  private String reviewedBy;
  private String reviewedAt;

  @Column(length = 4000)
  private String reviewNote;

  protected HumanOverrideJpaEntity() {}

  public HumanOverrideJpaEntity(
      String id, String s, String r, String p, String why, String out, String at) {
    this.id = id;
    aiSystemId = s;
    aosReference = r;
    supervisor = p;
    reason = why;
    outcome = out;
    occurredAt = at;
  }

  public void review(String by, String note, String at) {
    reviewedBy = by;
    reviewNote = note;
    reviewedAt = at;
  }

  public String getId() {
    return id;
  }

  public String getAiSystemId() {
    return aiSystemId;
  }

  public String getAosReference() {
    return aosReference;
  }

  public String getSupervisor() {
    return supervisor;
  }

  public String getReason() {
    return reason;
  }

  public String getOutcome() {
    return outcome;
  }

  public String getOccurredAt() {
    return occurredAt;
  }

  public String getReviewedBy() {
    return reviewedBy;
  }

  public String getReviewedAt() {
    return reviewedAt;
  }

  public String getReviewNote() {
    return reviewNote;
  }
}
