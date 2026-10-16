package com.prudence.posture.domain;

import java.util.List;

/// Policy-drift report: executions observed in the evidence plane that diverge from the
/// governance record. The sharp signal is an execution tagged with a system that is **not
/// approved** (or not even registered) — an ungoverned run.
/// `complete` is false when the evidence scan could not read everything: the findings are then
/// a lower bound, and the absence of violations proves nothing.
public record Drift(
    String generatedAt,
    boolean hasViolations,
    boolean complete,
    String scanDetail,
    List<Finding> findings) {

  public record Finding(
      String aiSystemId,
      String name,
      String approvalStatus,
      long executionCount,
      String severity, // VIOLATION | OK
      String detail) {}
}
