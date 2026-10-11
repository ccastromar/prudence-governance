package com.prudence.registry.application.port.in;

import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.AiSystemId;

/// Driving port: a governance reviewer approves or rejects a system awaiting a decision.
/// This is the human side of the control loop — once a high-risk system is approved here,
/// the PDP returns ALLOW and the AOS gate lets the pipeline run.
public interface ReviewAiSystemUseCase {
    AiSystem approve(AiSystemId id, String reviewer);
    AiSystem reject(AiSystemId id, String reviewer, String reason);
}
