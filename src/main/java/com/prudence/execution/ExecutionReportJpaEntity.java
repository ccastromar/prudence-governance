package com.prudence.execution;

import jakarta.persistence.*;

/// One execution a runtime reported: what ran, when, and under which recorded decision.
/// `decisionStatus` is what Prudence found for the cited decision when the report arrived:
/// the verdict (ALLOW, DENY, APPROVAL_REQUIRED), NONE when no decision was cited, UNKNOWN
/// when the id matches no recorded decision, OTHER_SYSTEM when it was about another system.
@Entity
@Table(name = "execution_report")
public class ExecutionReportJpaEntity {
  @Id private String id;
  private String runtime;
  private String executionId;
  private String decisionId;
  private String decisionStatus;
  private String aiSystemId;
  private String pipeline;
  private String startedAt;
  private String reportedBy;
  private String receivedAt;

  protected ExecutionReportJpaEntity() {}

  public ExecutionReportJpaEntity(
      String id,
      String runtime,
      String executionId,
      String decisionId,
      String decisionStatus,
      String aiSystemId,
      String pipeline,
      String startedAt,
      String reportedBy,
      String receivedAt) {
    this.id = id;
    this.runtime = runtime;
    this.executionId = executionId;
    this.decisionId = decisionId;
    this.decisionStatus = decisionStatus;
    this.aiSystemId = aiSystemId;
    this.pipeline = pipeline;
    this.startedAt = startedAt;
    this.reportedBy = reportedBy;
    this.receivedAt = receivedAt;
  }

  public String getId() {
    return id;
  }

  public String getRuntime() {
    return runtime;
  }

  public String getExecutionId() {
    return executionId;
  }

  public String getDecisionId() {
    return decisionId;
  }

  public String getDecisionStatus() {
    return decisionStatus;
  }

  public String getAiSystemId() {
    return aiSystemId;
  }

  public String getPipeline() {
    return pipeline;
  }

  public String getStartedAt() {
    return startedAt;
  }

  public String getReportedBy() {
    return reportedBy;
  }

  public String getReceivedAt() {
    return receivedAt;
  }
}
