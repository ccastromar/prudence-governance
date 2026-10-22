package cc.ccmlab.prudence.policy.adapter.out.audit;

import cc.ccmlab.prudence.evidence.delivery.Delivery;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "policy_audit_outbox")
public class PolicyAuditEntity {
  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false, length = 36)
  private String aggregateId;

  @Column(nullable = false, length = 32)
  private String eventType;

  @Column(nullable = false, length = 512)
  private String actor;

  @Column(nullable = false)
  private String occurredAt;

  @Column(nullable = false, columnDefinition = "text")
  private String detailsJson;

  @Embedded private Delivery delivery;

  protected PolicyAuditEntity() {}

  public PolicyAuditEntity(String aggregateId, String eventType, String actor, String detailsJson) {
    this.id = UUID.randomUUID().toString();
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.actor = actor;
    this.detailsJson = detailsJson;
    this.occurredAt = Delivery.stamp(Instant.now());
    this.delivery = Delivery.pending();
  }

  public String getId() {
    return id;
  }

  public String getAggregateId() {
    return aggregateId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getActor() {
    return actor;
  }

  public String getOccurredAt() {
    return occurredAt;
  }

  public String getDetailsJson() {
    return detailsJson;
  }

  public Delivery getDelivery() {
    return delivery;
  }
}
