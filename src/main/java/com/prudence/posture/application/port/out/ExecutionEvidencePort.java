package com.prudence.posture.application.port.out;

import java.util.Map;

/// ACL over the evidence plane: how many runtime *execution* evidence blocks are tagged with
/// each AI system id. Lets oversight detect executions that happened outside governance.
public interface ExecutionEvidencePort {

  /// Scans the evidence plane. A scan that could not read everything (Ernest unreachable,
  /// throttled, failed mid-way) says so: partial counts must never pass for a clean result.
  ExecutionScan scan();

  /// `counts`: aiSystemId -> execution-stage blocks seen. `complete` is false when the scan
  /// stopped early; `detail` explains why.
  record ExecutionScan(Map<String, Long> counts, boolean complete, String detail) {}
}
