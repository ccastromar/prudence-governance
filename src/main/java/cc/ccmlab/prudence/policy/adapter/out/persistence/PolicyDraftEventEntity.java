package cc.ccmlab.prudence.policy.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "policy_draft_event")
public class PolicyDraftEventEntity {
  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false, length = 36)
  private String policyId;

  @Column(nullable = false, length = 36)
  private String revisionId;

  @Column(nullable = false, length = 24)
  private String eventType;

  @Column(nullable = false, length = 512)
  private String actor;

  @Column(nullable = false)
  private OffsetDateTime occurredAt;

  @Column(nullable = false, columnDefinition = "text")
  private String rulesJson;

  protected PolicyDraftEventEntity() {}

  public PolicyDraftEventEntity(
      String policyId,
      String revisionId,
      String type,
      String actor,
      OffsetDateTime at,
      String rules) {
    this.id = UUID.randomUUID().toString();
    this.policyId = policyId;
    this.revisionId = revisionId;
    this.eventType = type;
    this.actor = actor;
    this.occurredAt = at;
    this.rulesJson = rules;
  }

  public String getId() {
    return id;
  }

  public String getPolicyId() {
    return policyId;
  }

  public String getRevisionId() {
    return revisionId;
  }

  public String getRulesJson() {
    return rulesJson;
  }

  public String getEventType() {
    return eventType;
  }

  public String getActor() {
    return actor;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }
}
