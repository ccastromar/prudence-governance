package com.prudence.registry.application.port.in;

import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.AiSystemId;

/// Driving port: a governance reviewer approves or rejects a system awaiting a decision.
/// This is the human side of the control loop — once a high-risk system is approved here,
/// the PDP returns ALLOW and the AOS gate lets the pipeline run.
public interface ReviewAiSystemUseCase {
    AiSystem submitForReview(AiSystemId id);
    AiSystem approve(AiSystemId id, String reviewer);
    AiSystem reject(AiSystemId id, String reviewer, String reason);

    /// Periodic review: renew a still-valid approval without interrupting the system.
    AiSystem renew(AiSystemId id, String reviewer);

    /// Take the system out of service immediately (e.g. a serious incident); audited.
    AiSystem suspend(AiSystemId id, String actor, String reason);
}
