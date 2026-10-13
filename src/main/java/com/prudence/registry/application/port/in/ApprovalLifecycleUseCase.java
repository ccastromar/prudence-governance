package com.prudence.registry.application.port.in;

import com.prudence.registry.domain.AiSystemId;

import java.time.Instant;
import java.util.List;

/// Driving port for the periodic-review lifecycle, run by a scheduler: expire lapsed approvals
/// and issue the renewal notice when an approval enters its review window. Each system is
/// handled in its own transaction so one failure cannot hold back the rest of the sweep.
public interface ApprovalLifecycleUseCase {
    List<AiSystemId> approvedSystems();

    /// Applies at most one lifecycle step to the system; returns what happened.
    Outcome applyLifecycle(AiSystemId id, Instant now);

    enum Outcome { NONE, EXPIRED, REVIEW_DUE }
}
