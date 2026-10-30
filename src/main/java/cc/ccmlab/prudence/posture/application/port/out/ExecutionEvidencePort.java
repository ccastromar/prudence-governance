package cc.ccmlab.prudence.posture.application.port.out;

import java.util.Map;

/// ACL over the evidence plane: how many runtime *execution* evidence blocks are tagged with
/// each AI system id. Lets oversight detect executions that happened outside governance.
public interface ExecutionEvidencePort {

  /// Scans the evidence plane. A scan that could not read everything (Ernest unreachable,
  /// throttled, failed mid-way) says so: partial counts must never pass for a clean result.
  ExecutionScan scan();

  /// `counts`: aiSystemId -> execution-stage blocks seen. `lastExecutionAt`: aiSystemId -> ISO
  /// time of its latest execution, when the evidence plane can tell (absent = unknown).
  /// `complete` is false when the scan stopped early; `detail` explains why. `outsideDecision`:
  /// aiSystemId -> decision status -> executions known to have run without an ALLOW decision.
  record ExecutionScan(
      Map<String, Long> counts,
      Map<String, String> lastExecutionAt,
      boolean complete,
      String detail,
      Map<String, Map<String, Long>> outsideDecision) {

    public ExecutionScan(
        Map<String, Long> counts,
        Map<String, String> lastExecutionAt,
        boolean complete,
        String detail) {
      this(counts, lastExecutionAt, complete, detail, Map.of());
    }

    public ExecutionScan(Map<String, Long> counts, boolean complete, String detail) {
      this(counts, Map.of(), complete, detail);
    }
  }
}
