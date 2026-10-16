package com.prudence.incident.adapter.out.persistence;

import com.prudence.evidence.delivery.Delivery;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// An append-only incident timeline entry, which is also its own evidence outbox. Only the
/// receipt (`sealed`, `blockIndex`) is written after the fact.
@Entity
@Table(name = "incident_event")
public class IncidentEventJpaEntity {
  @Id private String id;
  private String incidentId;
  private String aiSystemId;
  private String type;
  private String actor;
  private String at;

  @Column(length = 4000)
  private String details;

  private boolean sealed;
  private Long blockIndex;
  @Embedded private Delivery delivery;

  protected IncidentEventJpaEntity() {}

  public IncidentEventJpaEntity(
      String id,
      String incidentId,
      String aiSystemId,
      String type,
      String actor,
      String at,
      String details) {
    this.id = id;
    this.incidentId = incidentId;
    this.aiSystemId = aiSystemId;
    this.type = type;
    this.actor = actor;
    this.at = at;
    this.details = details;
    this.delivery = Delivery.pending();
  }

  public void recordSeal(boolean qualified, Long blockIndex) {
    this.sealed = qualified;
    this.blockIndex = blockIndex;
  }

  public String getId() {
    return id;
  }

  public String getIncidentId() {
    return incidentId;
  }

  public String getAiSystemId() {
    return aiSystemId;
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

  public boolean isSealed() {
    return sealed;
  }

  public Long getBlockIndex() {
    return blockIndex;
  }

  public Delivery getDelivery() {
    return delivery;
  }
}
