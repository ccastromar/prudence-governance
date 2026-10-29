package cc.ccmlab.prudence.demo.adapter.out.records;

import cc.ccmlab.prudence.evidence.adapter.out.persistence.EvidenceOutboxJpaRepository;
import cc.ccmlab.prudence.evidence.delivery.Delivery;
import cc.ccmlab.prudence.execution.ExecutionReportJpaEntity;
import cc.ccmlab.prudence.execution.ExecutionReportRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What Prudence itself recorded for a governed demo run: the PDP decision and the execution the
 * runtime reported. The console shows these next to the runner's account, so a demo proves
 * governance from Prudence's side.
 *
 * <p>A run's decision is the first one for its system and pipeline after the run started and before
 * Prudence saw it finished; its execution is the first one its runtime reported after the run
 * started. Every demo shares a pipeline, so the time window is what keeps one demo from seeing
 * another's decision; two runs of the same system at the same moment could still overlap.
 */
@Component
public class DemoRunRecords {
  private final EvidenceOutboxJpaRepository decisions;
  private final ExecutionReportRepository executions;

  public DemoRunRecords(
      EvidenceOutboxJpaRepository decisions, ExecutionReportRepository executions) {
    this.decisions = decisions;
    this.executions = executions;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> forRun(
      String aiSystemId, String pipeline, String runtime, Instant startedAt, Instant finishedAt) {
    Map<String, Object> seen = new LinkedHashMap<>();
    decisions
        .findByAiSystemIdAndPipelineAndCreatedAtBetweenOrderByCreatedAtAsc(
            aiSystemId,
            pipeline,
            Delivery.stamp(startedAt),
            Delivery.stamp(finishedAt),
            Limit.of(1))
        .stream()
        .findFirst()
        .ifPresent(
            d -> {
              Map<String, Object> decision = new LinkedHashMap<>();
              decision.put("decisionId", d.getId());
              decision.put("verdict", d.getVerdict());
              decision.put("reason", d.getReason());
              decision.put("subject", d.getSubject());
              decision.put("at", d.getCreatedAt());
              seen.put("decision", decision);
            });
    executions.findByAiSystemIdOrderByStartedAtDesc(aiSystemId, Limit.of(50)).stream()
        .filter(
            e ->
                runtime.equals(e.getRuntime())
                    && pipeline.equals(e.getPipeline())
                    && !before(e.getReceivedAt(), startedAt))
        .min(Comparator.comparing(ExecutionReportJpaEntity::getReceivedAt))
        .ifPresent(
            e -> {
              Map<String, Object> execution = new LinkedHashMap<>();
              execution.put("runtime", e.getRuntime());
              execution.put("executionId", e.getExecutionId());
              execution.put("decisionId", e.getDecisionId());
              execution.put("decisionStatus", e.getDecisionStatus());
              execution.put("at", e.getReceivedAt());
              seen.put("execution", execution);
            });
    return seen;
  }

  private static boolean before(String at, Instant start) {
    try {
      return Instant.parse(at).isBefore(start);
    } catch (DateTimeParseException | NullPointerException e) {
      return true;
    }
  }
}
