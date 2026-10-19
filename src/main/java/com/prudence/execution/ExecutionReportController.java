package com.prudence.execution;

import com.prudence.evidence.adapter.out.persistence.EvidenceOutboxJpaRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/// Execution reports (Decision API v1, second call): after a runtime runs what Prudence
/// decided on, it reports the execution and cites the decision. These reports are an execution
/// source for drift that does not depend on Ernest. A report is accepted whatever the cited
/// decision was — an execution after a DENY, or without any decision, is exactly what drift
/// must see — and records what Prudence found for that decision.
@RestController
@RequestMapping("/api/executions")
public class ExecutionReportController {

  /// A start time this far ahead of Prudence's clock is a broken clock, not an execution.
  private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

  private final ExecutionReportRepository reports;
  private final EvidenceOutboxJpaRepository decisions;

  public ExecutionReportController(
      ExecutionReportRepository reports, EvidenceOutboxJpaRepository decisions) {
    this.reports = reports;
    this.decisions = decisions;
  }

  /// 201 for a new report; 200 when the same execution was already reported identically
  /// (safe retries); 409 when that (runtime, executionId) was reported with different facts.
  @PostMapping
  @Transactional
  public ResponseEntity<ExecutionReportJpaEntity> report(
      @Valid @RequestBody ExecutionReportRequest request, Authentication authentication) {
    String startedAt = normalizedStart(request.startedAt());
    String reportedBy = authentication != null ? authentication.getName() : "anonymous-dev";

    var existing = reports.findByRuntimeAndExecutionId(request.runtime(), request.executionId());
    if (existing.isPresent()) {
      var r = existing.get();
      boolean same =
          r.getAiSystemId().equals(request.aiSystemId())
              && r.getPipeline().equals(request.pipeline())
              && r.getStartedAt().equals(startedAt)
              && Objects.equals(r.getDecisionId(), blankToNull(request.decisionId()));
      if (!same) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "execution "
                + request.executionId()
                + " of "
                + request.runtime()
                + " was already reported with different facts");
      }
      return ResponseEntity.ok(r);
    }

    String decisionId = blankToNull(request.decisionId());
    var saved =
        reports.save(
            new ExecutionReportJpaEntity(
                UUID.randomUUID().toString(),
                request.runtime(),
                request.executionId(),
                decisionId,
                decisionStatus(decisionId, request.aiSystemId()),
                request.aiSystemId(),
                request.pipeline(),
                startedAt,
                reportedBy,
                Instant.now().toString()));
    return ResponseEntity.status(HttpStatus.CREATED).body(saved);
  }

  /// The latest reported executions of one AI system.
  @GetMapping
  public List<ExecutionReportJpaEntity> list(
      @RequestParam String aiSystemId, @RequestParam(defaultValue = "50") int limit) {
    return reports.findByAiSystemIdOrderByStartedAtDesc(
        aiSystemId, Limit.of(Math.max(1, Math.min(limit, 500))));
  }

  private String decisionStatus(String decisionId, String aiSystemId) {
    if (decisionId == null) return "NONE";
    return decisions
        .findById(decisionId)
        .map(d -> d.getAiSystemId().equals(aiSystemId) ? d.getVerdict() : "OTHER_SYSTEM")
        .orElse("UNKNOWN");
  }

  /// ISO-8601 instant, stored in Instant's own UTC form so string order is time order.
  private static String normalizedStart(String raw) {
    Instant at;
    try {
      at = Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "startedAt must be an ISO-8601 instant, e.g. 2026-10-19T10:15:30Z");
    }
    if (at.isAfter(Instant.now().plus(MAX_CLOCK_SKEW))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startedAt is in the future");
    }
    return at.toString();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /// `decisionId` is optional: a runtime that ran without asking still has to say so.
  public record ExecutionReportRequest(
      @NotBlank String runtime,
      @NotBlank String executionId,
      String decisionId,
      @NotBlank String aiSystemId,
      @NotBlank String pipeline,
      @NotBlank String startedAt) {}
}
