package com.prudence.posture.domain;

import java.util.List;

/// Policy-drift report: executions observed in the evidence plane that diverge from the
/// governance record. The sharp signal is an execution tagged with a system that is **not
/// approved** (or not even registered) — an ungoverned run.
public record Drift(String generatedAt, boolean hasViolations, List<Finding> findings) {

    public record Finding(
            String aiSystemId,
            String name,
            String approvalStatus,
            long executionCount,
            String severity,   // VIOLATION | OK
            String detail) {}
}
