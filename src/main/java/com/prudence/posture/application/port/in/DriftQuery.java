package com.prudence.posture.application.port.in;

import com.prudence.posture.domain.Drift;

/// Driving port: detect policy drift — executions that diverge from the governance record.
public interface DriftQuery {
    Drift compute();
}
