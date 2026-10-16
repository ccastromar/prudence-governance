package com.prudence.registry.adapter.out.persistence;

import com.prudence.evidence.delivery.Delivery;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// JPA row for an append-only governance review event. The row is also its own evidence
/// outbox: `delivery` tracks it on its way to Ernest, and only the receipt (`sealed`,
/// `blockIndex`) is written after the fact — the review itself is never modified.
@Entity
@Table(name = "review_event")
public class ReviewEventJpaEntity {

  @Id private String id;
  private String aiSystemId;
  private String action;
  private String reviewer;

  @Column(length = 2000)
  private String reason;

  private String at;
  private String previousStatus;
  private String newStatus;
  private boolean sealed;
  private Long blockIndex;
  @Embedded private Delivery delivery;

  protected ReviewEventJpaEntity() {}

  public ReviewEventJpaEntity(
      String id,
      String aiSystemId,
      String action,
      String reviewer,
      String reason,
      String at,
      String previousStatus,
      String newStatus) {
    this.id = id;
    this.aiSystemId = aiSystemId;
    this.action = action;
    this.reviewer = reviewer;
    this.reason = reason;
    this.at = at;
    this.previousStatus = previousStatus;
    this.newStatus = newStatus;
    this.delivery = Delivery.pending();
  }

  /// Ernest's receipt for this event.
  public void recordSeal(boolean qualified, Long blockIndex) {
    this.sealed = qualified;
    this.blockIndex = blockIndex;
  }

  public String getId() {
    return id;
  }

  public String getAiSystemId() {
    return aiSystemId;
  }

  public String getAction() {
    return action;
  }

  public String getReviewer() {
    return reviewer;
  }

  public String getReason() {
    return reason;
  }

  public String getAt() {
    return at;
  }

  public String getPreviousStatus() {
    return previousStatus;
  }

  public String getNewStatus() {
    return newStatus;
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
