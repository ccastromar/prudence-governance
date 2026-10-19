package cc.ccmlab.prudence.posture.application.port.in;

import cc.ccmlab.prudence.posture.domain.Drift;

/// Driving port: detect policy drift — executions that diverge from the governance record.
public interface DriftQuery {
  Drift compute();
}
