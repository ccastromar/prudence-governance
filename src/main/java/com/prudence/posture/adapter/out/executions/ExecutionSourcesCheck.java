package com.prudence.posture.adapter.out.executions;

import com.prudence.execution.ExecutionReportRepository;
import com.prudence.posture.adapter.out.ernest.ErnestEvidenceHealthAdapter;
import com.prudence.posture.application.port.out.SystemInventoryPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/// Cross-checks the two execution sources (ADR-001): what runtimes reported to Prudence and
/// the execution evidence Ernest holds. They should tell the same story, so a mismatch is a
/// monitoring warning, not a violation (drift already flags ungoverned runs from either one).
///
/// Ernest only keeps, per AI system, a running count and the latest execution time, and
/// runtimes only started reporting recently, so the check compares latest times, not totals:
///   - REPORTED_NOT_EVIDENCED: a runtime reported an execution more than `grace` ago, and Ernest
///     has none that recent: its evidence never arrived (e.g. evidence shipping is broken).
///   - EVIDENCED_NOT_REPORTED: Ernest holds an execution more than `grace` old that is later than
///     anything the runtimes reported for a system they do report on: it ran without telling
///     Prudence.
/// `grace` covers Kafka and batching lag; `tolerance` the few seconds between a runtime's
/// report and its own evidence timestamp.
@Component
public class ExecutionSourcesCheck {

  private static final Duration TOLERANCE = Duration.ofMinutes(2);

  private final ErnestEvidenceHealthAdapter ernest;
  private final ExecutionReportRepository reports;
  private final SystemInventoryPort inventory;
  private final Duration grace;

  public ExecutionSourcesCheck(
      ErnestEvidenceHealthAdapter ernest,
      ExecutionReportRepository reports,
      SystemInventoryPort inventory,
      @Value("${prudence.drift.source-grace:PT15M}") Duration grace) {
    this.ernest = ernest;
    this.reports = reports;
    this.inventory = inventory;
    this.grace = grace;
  }

  public record Mismatch(
      String aiSystemId,
      String name,
      String kind,
      String lastReportedAt,
      String lastEvidencedAt,
      String detail) {}

  /// `complete` false: Ernest could not be read in full, so no comparison was made.
  public record Result(
      String checkedAt, boolean complete, String detail, long graceMinutes, List<Mismatch> mismatches) {}

  public Result check() {
    Instant now = Instant.now();
    var evidence = ernest.scan();
    if (!evidence.complete()) {
      return new Result(
          now.toString(),
          false,
          "Ernest's execution evidence could not be read in full: "
              + evidence.detail()
              + ". The two sources were not compared.",
          grace.toMinutes(),
          List.of());
    }
    Map<String, String> names = new HashMap<>();
    for (var s : inventory.all()) names.put(s.id(), s.name());

    List<Mismatch> out = new ArrayList<>();
    for (var row : reports.perSystem()) {
      String id = row.getAiSystemId();
      Instant reported = parse(row.getLastStartedAt());
      Instant evidenced = parse(evidence.lastExecutionAt().get(id));
      String name = names.getOrDefault(id, "(unregistered)");
      if (reported == null) continue;

      if (reported.isBefore(now.minus(grace))
          && (evidenced == null || evidenced.isBefore(reported.minus(TOLERANCE)))) {
        out.add(
            new Mismatch(
                id,
                name,
                "REPORTED_NOT_EVIDENCED",
                reported.toString(),
                evidenced == null ? null : evidenced.toString(),
                "A runtime reported an execution at "
                    + reported
                    + ", but Ernest holds "
                    + (evidenced == null ? "no execution evidence" : "nothing later than " + evidenced)
                    + " for this system: its evidence never arrived."));
      } else if (evidenced != null
          && evidenced.isBefore(now.minus(grace))
          && evidenced.isAfter(reported.plus(TOLERANCE))) {
        out.add(
            new Mismatch(
                id,
                name,
                "EVIDENCED_NOT_REPORTED",
                reported.toString(),
                evidenced.toString(),
                "Ernest holds an execution at "
                    + evidenced
                    + ", but the latest one runtimes reported was at "
                    + reported
                    + ": it ran without reporting to Prudence."));
      }
    }
    String detail =
        out.isEmpty()
            ? "Runtime reports and Ernest's execution evidence agree."
            : out.size() + " system(s) where the two execution sources disagree.";
    return new Result(now.toString(), true, detail, grace.toMinutes(), out);
  }

  private static Instant parse(String iso) {
    if (iso == null || iso.isBlank()) return null;
    try {
      return Instant.parse(iso);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
