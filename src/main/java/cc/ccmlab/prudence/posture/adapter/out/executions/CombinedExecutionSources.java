package cc.ccmlab.prudence.posture.adapter.out.executions;

import cc.ccmlab.prudence.execution.ExecutionReportRepository;
import cc.ccmlab.prudence.posture.adapter.out.ernest.ErnestEvidenceHealthAdapter;
import cc.ccmlab.prudence.posture.application.port.out.ExecutionEvidencePort;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/// Drift's execution source: Ernest's execution evidence plus the executions runtimes reported
/// to Prudence (ADR-001). Either source showing an execution counts. Both usually see the same
/// runs, so per system the larger count is taken (not the sum) and the latest time of either.
/// The scan is only as complete as Ernest's: an incomplete evidence scan still says so. With a
/// custody-only evidence store (no Ernest, ADR-001 C1) the runtime reports are the only source.
@Component
@Primary
public class CombinedExecutionSources implements ExecutionEvidencePort {

  private final ObjectProvider<ErnestEvidenceHealthAdapter> ernest;
  private final ExecutionReportRepository reports;

  public CombinedExecutionSources(
      ObjectProvider<ErnestEvidenceHealthAdapter> ernest, ExecutionReportRepository reports) {
    this.ernest = ernest;
    this.reports = reports;
  }

  @Override
  public ExecutionScan scan() {
    ErnestEvidenceHealthAdapter store = ernest.getIfAvailable();
    ExecutionScan evidence =
        store != null
            ? store.scan()
            : new ExecutionScan(
                Map.of(),
                Map.of(),
                true,
                "The evidence store cannot be read back (custody only); runtime reports only");
    Map<String, Long> counts = new HashMap<>(evidence.counts());
    Map<String, String> last = new HashMap<>(evidence.lastExecutionAt());
    long reported = 0;
    for (var row : reports.perSystem()) {
      reported += row.getExecutions();
      counts.merge(row.getAiSystemId(), row.getExecutions(), Math::max);
      last.merge(row.getAiSystemId(), row.getLastStartedAt(), CombinedExecutionSources::later);
    }
    String detail =
        (evidence.detail() == null || evidence.detail().isBlank() ? "" : evidence.detail() + " · ")
            + reported
            + " execution(s) reported by runtimes";
    return new ExecutionScan(counts, last, evidence.complete(), detail);
  }

  /// The later of two ISO-8601 times; an unparsable one never wins over a known time.
  private static String later(String a, String b) {
    Instant x = parse(a), y = parse(b);
    if (x == null) return y == null ? a : b;
    if (y == null) return a;
    return y.isAfter(x) ? b : a;
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
