package cc.ccmlab.prudence.governance.adapter.out.persistence;

import cc.ccmlab.prudence.governance.domain.GovernanceCaseStatus;
import cc.ccmlab.prudence.governance.domain.GovernanceCaseType;
import jakarta.persistence.*;

@Entity
@Table(name = "governance_case")
public class GovernanceCaseJpaEntity {
  @Id private String id;
  private String aiSystemId;

  @Enumerated(EnumType.STRING)
  private GovernanceCaseType type;

  @Enumerated(EnumType.STRING)
  private GovernanceCaseStatus status;

  @Column(length = 500)
  private String title;

  @Column(length = 4000)
  private String summary;

  private String owner;
  private String openedBy;
  private String openedAt;
  private String reviewStartedAt;
  private String closedAt;
  private String closedBy;

  @Column(length = 4000)
  private String closingDecision;

  protected GovernanceCaseJpaEntity() {}

  public GovernanceCaseJpaEntity(
      String id,
      String aiSystemId,
      GovernanceCaseType type,
      String title,
      String summary,
      String owner,
      String openedBy,
      String openedAt) {
    this.id = id;
    this.aiSystemId = aiSystemId;
    this.type = type;
    this.title = title;
    this.summary = summary;
    this.owner = owner;
    this.openedBy = openedBy;
    this.openedAt = openedAt;
    this.status = GovernanceCaseStatus.OPEN;
  }

  public void startReview(String at) {
    if (status != GovernanceCaseStatus.OPEN) throw new IllegalStateException("case is not open");
    status = GovernanceCaseStatus.IN_REVIEW;
    reviewStartedAt = at;
  }

  public void close(String actor, String decision, String at) {
    if (status == GovernanceCaseStatus.CLOSED)
      throw new IllegalStateException("case is already closed");
    status = GovernanceCaseStatus.CLOSED;
    closedBy = actor;
    closingDecision = decision;
    closedAt = at;
  }

  public String getId() {
    return id;
  }

  public String getAiSystemId() {
    return aiSystemId;
  }

  public GovernanceCaseType getType() {
    return type;
  }

  public GovernanceCaseStatus getStatus() {
    return status;
  }

  public String getTitle() {
    return title;
  }

  public String getSummary() {
    return summary;
  }

  public String getOwner() {
    return owner;
  }

  public String getOpenedBy() {
    return openedBy;
  }

  public String getOpenedAt() {
    return openedAt;
  }

  public String getReviewStartedAt() {
    return reviewStartedAt;
  }

  public String getClosedAt() {
    return closedAt;
  }

  public String getClosedBy() {
    return closedBy;
  }

  public String getClosingDecision() {
    return closingDecision;
  }
}
