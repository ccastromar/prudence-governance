package cc.ccmlab.prudence.alerting.adapter.out.persistence;

import cc.ccmlab.prudence.alerting.domain.AlertKind;
import cc.ccmlab.prudence.evidence.delivery.Delivery;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;

/// A raised alert, which is also its own outbox: `delivery` tracks it until the email is sent.
@Entity
@Table(name = "alert")
public class AlertJpaEntity {
  @Id private String id;

  @Enumerated(EnumType.STRING)
  private AlertKind kind;

  @Column(unique = true)
  private String dedupKey;

  private String aiSystemId;

  /// Comma-separated, resolved when the alert was raised.
  @Column(length = 2000)
  private String recipients;

  @Column(length = 500)
  private String subject;

  @Column(length = 4000)
  private String body;

  private String createdAt;
  @Embedded private Delivery delivery;

  protected AlertJpaEntity() {}

  public AlertJpaEntity(
      String id,
      AlertKind kind,
      String dedupKey,
      String aiSystemId,
      String recipients,
      String subject,
      String body) {
    this.id = id;
    this.kind = kind;
    this.dedupKey = dedupKey;
    this.aiSystemId = aiSystemId;
    this.recipients = recipients;
    this.subject = subject;
    this.body = body;
    this.createdAt = Delivery.stamp(Instant.now());
    this.delivery = Delivery.pending();
  }

  public String getId() {
    return id;
  }

  public AlertKind getKind() {
    return kind;
  }

  public String getDedupKey() {
    return dedupKey;
  }

  public String getAiSystemId() {
    return aiSystemId;
  }

  public String getRecipients() {
    return recipients;
  }

  public List<String> recipientList() {
    return recipients == null || recipients.isBlank() ? List.of() : List.of(recipients.split(","));
  }

  public String getSubject() {
    return subject;
  }

  public String getBody() {
    return body;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public Delivery getDelivery() {
    return delivery;
  }
}
