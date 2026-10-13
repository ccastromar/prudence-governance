package com.prudence.registry.adapter.in.scheduling;

import com.prudence.registry.application.port.in.ApprovalLifecycleUseCase;
import com.prudence.registry.application.port.in.ApprovalLifecycleUseCase.Outcome;
import com.prudence.registry.domain.AiSystemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/// Driving adapter: the periodic-review sweep. Expiry is already enforced at decision time
/// (the PDP stops allowing a lapsed approval immediately); the sweep makes it visible and
/// auditable, and issues the renewal notice ahead of time.
@Component
public class ApprovalLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalLifecycleScheduler.class);

    private final ApprovalLifecycleUseCase lifecycle;

    public ApprovalLifecycleScheduler(ApprovalLifecycleUseCase lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${prudence.governance.lifecycle-sweep-ms:3600000}",
               initialDelayString = "${prudence.governance.lifecycle-initial-delay-ms:60000}")
    public void sweep() {
        Instant now = Instant.now();
        int expired = 0, due = 0;
        for (AiSystemId id : lifecycle.approvedSystems()) {
            try {
                Outcome outcome = lifecycle.applyLifecycle(id, now);
                if (outcome == Outcome.EXPIRED) expired++;
                if (outcome == Outcome.REVIEW_DUE) due++;
            } catch (Exception e) {
                // One bad record must not stop the sweep; it is retried on the next pass.
                log.warn("lifecycle sweep failed for system {}: {}", id.value(), e.getMessage());
            }
        }
        if (expired > 0 || due > 0) log.info("lifecycle sweep: {} expired, {} renewal notice(s)", expired, due);
    }
}
