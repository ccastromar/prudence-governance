package cc.ccmlab.prudence.policy.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "policy_definition")
public class PolicyDefinitionEntity {
  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false, length = 200, unique = true)
  private String name;

  @Column(nullable = false, columnDefinition = "text")
  private String description;

  @Column(nullable = false, length = 512)
  private String createdBy;

  @Column(nullable = false)
  private OffsetDateTime createdAt;

  protected PolicyDefinitionEntity() {}

  public PolicyDefinitionEntity(
      String id, String name, String description, String actor, OffsetDateTime at) {
    this.id = id;
    this.name = name;
    this.description = description;
    this.createdBy = actor;
    this.createdAt = at;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
