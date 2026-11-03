package cc.ccmlab.prudence.posture.adapter.out.executions;

import cc.ccmlab.prudence.execution.ExecutionReportJpaEntity;
import cc.ccmlab.prudence.execution.ExecutionReportRepository;
import cc.ccmlab.prudence.posture.adapter.out.ernest.ErnestEvidenceHealthAdapter;
import cc.ccmlab.prudence.posture.application.port.out.SystemInventoryPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/// Cross-checks the two execution sources (ADR-001): what runtimes reported to Prudence and
/// the execution evidence Ernest holds. They should tell the same story, so a mismatch is a
/// monitoring warning, not a violation (drift already flags ungoverned runs from either one).
///
/// Per AI system it compares latest times, which works for every runtime:
///   - REPORTED_NOT_EVIDENCED: a runtime reported an execution more than `grace` ago, and Ernest
///     has none that recent: its evidence never arrived (e.g. evidence shipping is broken).
///   - EVIDENCED_NOT_REPORTED: Ernest holds an execution more than `grace` old that is later than
///     anything the runtimes reported for a system they do report on: it ran without telling
///     Prudence.
/// `grace` covers Kafka and batching lag; `tolerance` the few seconds between a runtime's
/// report and its own evidence timestamp. A latest time hides an earlier gap, so for runtimes
/// whose evidence carries their execution id as correlationId (`correlated-runtimes`: AOS and
/// the default runtime names of the integrations in integrations/, which pass the same id to
/// report_execution and log_execution) each execution reported within `execution-id-horizon` is
/// also matched by id. A runtime renamed in its integration has to be added to that list.
@Component
public class ExecutionSourcesCheck {

  private static final Duration TOLERANCE = Duration.ofMinutes(2);

  private final ObjectProvider<ErnestEvidenceHealthAdapter> ernest;
  private final ExecutionReportRepository reports;
  private final SystemInventoryPort inventory;
  private final Duration grace;
  private final Duration horizon;
  private final Set<String> correlatedRuntimes;

  public ExecutionSourcesCheck(
      ObjectProvider<ErnestEvidenceHealthAdapter> ernest,
      ExecutionReportRepository reports,
      SystemInventoryPort inventory,
      @Value("${prudence.drift.source-grace:PT15M}") Duration grace,
      @Value("${prudence.drift.execution-id-horizon:P7D}") Duration horizon,
      @Value(
              "${prudence.drift.correlated-runtimes:aos,langgraph,langchain,crewai,llamaindex,"
                  + "smolagents,agent-framework,pydantic-ai,claude-agent-sdk,agno,google-adk,"
                  + "openai-agents,strands-agents}")
          List<String> correlatedRuntimes) {
    this.ernest = ernest;
    this.reports = reports;
    this.inventory = inventory;
    this.grace = grace;
    this.horizon = horizon;
    this.correlatedRuntimes = Set.copyOf(correlatedRuntimes);
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
      String checkedAt,
      boolean complete,
      String detail,
      long graceMinutes,
      List<Mismatch> mismatches) {}

  public Result check() {
    Instant now = Instant.now();
    ErnestEvidenceHealthAdapter store = ernest.getIfAvailable();
    if (store == null) {
      return new Result(
          now.toString(),
          false,
          "The evidence store cannot be read back (custody only): there is nothing to compare"
              + " runtime reports with.",
          grace.toMinutes(),
          List.of());
    }
    var evidence = store.scan();
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
                    + (evidenced == null
                        ? "no execution evidence"
                        : "nothing later than " + evidenced)
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
    String perExecution =
        missingEvidencePerExecution(store, now, names, evidence.lastExecutionAt(), out);
    String detail =
        (out.isEmpty()
                ? "Runtime reports and Ernest's execution evidence agree."
                : out.size() + " system(s) where the two execution sources disagree.")
            + (perExecution == null ? "" : " " + perExecution);
    return new Result(now.toString(), true, detail, grace.toMinutes(), out);
  }

  /// Latest times hide a gap that a later execution covers. For runtimes that send their
  /// execution id as the evidence's correlationId, each execution reported within `horizon`
  /// (and older than `grace`) is looked up in Ernest by that id. Adds a REPORTED_NOT_EVIDENCED
  /// per system with missing ones, unless the latest-time rule already flagged it. Returns a
  /// note when the ids could not be read, or null.
  private String missingEvidencePerExecution(
      ErnestEvidenceHealthAdapter store,
      Instant now,
      Map<String, String> names,
      Map<String, String> lastEvidenced,
      List<Mismatch> out) {
    if (correlatedRuntimes.isEmpty()) return null;
    Instant from = now.minus(horizon);
    var reported =
        reports.findByStartedAtBetween(from.toString(), now.minus(grace).toString()).stream()
            .filter(r -> correlatedRuntimes.contains(r.getRuntime()))
            .toList();
    if (reported.isEmpty()) return null;
    var evidence = store.executionIdsSince(from.minus(TOLERANCE));
    if (!evidence.available()) {
      return "Executions were not matched one by one: " + evidence.detail() + ".";
    }
    Map<String, List<ExecutionReportJpaEntity>> missing = new TreeMap<>();
    for (var r : reported) {
      if (!evidence.ids().getOrDefault(r.getAiSystemId(), Set.of()).contains(r.getExecutionId())) {
        missing.computeIfAbsent(r.getAiSystemId(), x -> new ArrayList<>()).add(r);
      }
    }
    Set<String> flagged = new HashSet<>();
    for (var m : out) if ("REPORTED_NOT_EVIDENCED".equals(m.kind())) flagged.add(m.aiSystemId());
    missing.forEach(
        (id, runs) -> {
          if (flagged.contains(id)) return;
          runs.sort(Comparator.comparing(ExecutionReportJpaEntity::getStartedAt));
          String latest = runs.get(runs.size() - 1).getStartedAt();
          String sample =
              String.join(
                  ", ",
                  runs.stream().limit(3).map(ExecutionReportJpaEntity::getExecutionId).toList());
          out.add(
              new Mismatch(
                  id,
                  names.getOrDefault(id, "(unregistered)"),
                  "REPORTED_NOT_EVIDENCED",
                  latest,
                  lastEvidenced.get(id),
                  runs.size()
                      + " execution(s) reported since "
                      + from
                      + " have no evidence in Ernest (e.g. "
                      + sample
                      + "): their evidence never arrived, although later ones did."));
        });
    return null;
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
