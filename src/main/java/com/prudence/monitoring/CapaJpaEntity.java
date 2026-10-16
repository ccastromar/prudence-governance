package com.prudence.monitoring;

import jakarta.persistence.*;

@Entity
@Table(name = "capa")
public class CapaJpaEntity {
  @Id private String id;
  private String aiSystemId;
  private String signalId;

  @Column(length = 4000)
  private String risk;

  @Column(length = 4000)
  private String mitigation;

  private String owner;
  private String dueAt;

  @Enumerated(EnumType.STRING)
  private CapaStatus status;

  @Column(length = 4000)
  private String effectivenessEvidence;

  private String closedAt;

  protected CapaJpaEntity() {}

  public CapaJpaEntity(
      String id,
      String system,
      String signal,
      String risk,
      String mitigation,
      String owner,
      String due) {
    this.id = id;
    aiSystemId = system;
    signalId = signal;
    this.risk = risk;
    this.mitigation = mitigation;
    this.owner = owner;
    dueAt = due;
    status = CapaStatus.OPEN;
  }

  public void implement() {
    status = CapaStatus.IMPLEMENTED;
  }

  public void verify(String e) {
    effectivenessEvidence = e;
    status = CapaStatus.EFFECTIVENESS_VERIFIED;
  }

  public String getId() {
    return id;
  }

  public String getAiSystemId() {
    return aiSystemId;
  }

  public String getSignalId() {
    return signalId;
  }

  public String getRisk() {
    return risk;
  }

  public String getMitigation() {
    return mitigation;
  }

  public String getOwner() {
    return owner;
  }

  public String getDueAt() {
    return dueAt;
  }

  public CapaStatus getStatus() {
    return status;
  }

  public String getEffectivenessEvidence() {
    return effectivenessEvidence;
  }
}
