package com.prudence.governance.adapter.out.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "governance_case_event")
public class GovernanceCaseEventJpaEntity {
  @Id private String id;
  private String caseId;
  private String type;
  private String actor;
  private String at;

  @Column(length = 4000)
  private String details;

  protected GovernanceCaseEventJpaEntity() {}

  public GovernanceCaseEventJpaEntity(
      String id, String caseId, String type, String actor, String at, String details) {
    this.id = id;
    this.caseId = caseId;
    this.type = type;
    this.actor = actor;
    this.at = at;
    this.details = details;
  }

  public String getId() {
    return id;
  }

  public String getCaseId() {
    return caseId;
  }

  public String getType() {
    return type;
  }

  public String getActor() {
    return actor;
  }

  public String getAt() {
    return at;
  }

  public String getDetails() {
    return details;
  }
}
