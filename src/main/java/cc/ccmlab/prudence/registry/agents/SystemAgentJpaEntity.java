package cc.ccmlab.prudence.registry.agents;

import jakarta.persistence.*;

/// One agent declared as acting for one AI system (`contract/identity/` section 5).
@Entity
@Table(
    name = "ai_system_agent",
    uniqueConstraints =
        @UniqueConstraint(
            name = "ai_system_agent_unique",
            columnNames = {"ai_system_id", "agent_id"}),
    indexes = @Index(name = "ai_system_agent_agent_idx", columnList = "agent_id"))
public class SystemAgentJpaEntity {
  @Id private String id;

  @Column(nullable = false)
  private String aiSystemId;

  @Column(nullable = false)
  private String agentId;

  @Column(length = 512)
  private String declaredBy;

  @Column(nullable = false)
  private String declaredAt;

  protected SystemAgentJpaEntity() {}

  public SystemAgentJpaEntity(
      String id, String aiSystemId, String agentId, String declaredBy, String declaredAt) {
    this.id = id;
    this.aiSystemId = aiSystemId;
    this.agentId = agentId;
    this.declaredBy = declaredBy;
    this.declaredAt = declaredAt;
  }

  public String getAiSystemId() {
    return aiSystemId;
  }

  public String getAgentId() {
    return agentId;
  }

  public String getDeclaredBy() {
    return declaredBy;
  }

  public String getDeclaredAt() {
    return declaredAt;
  }
}
