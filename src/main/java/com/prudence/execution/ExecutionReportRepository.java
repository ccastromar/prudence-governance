package com.prudence.execution;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExecutionReportRepository extends JpaRepository<ExecutionReportJpaEntity, String> {

  Optional<ExecutionReportJpaEntity> findByRuntimeAndExecutionId(String runtime, String executionId);

  List<ExecutionReportJpaEntity> findByAiSystemIdOrderByStartedAtDesc(String aiSystemId, Limit limit);

  /// Per system: how many executions were reported and the latest start (ISO-8601 UTC, so
  /// the string maximum is the latest instant).
  @Query(
      "select r.aiSystemId as aiSystemId, count(r) as executions, max(r.startedAt) as lastStartedAt"
          + " from ExecutionReportJpaEntity r group by r.aiSystemId")
  List<SystemExecutions> perSystem();

  interface SystemExecutions {
    String getAiSystemId();

    long getExecutions();

    String getLastStartedAt();
  }
}
