package com.prudence.posture.application.service;

import com.prudence.posture.application.port.in.PostureQuery;
import com.prudence.posture.application.port.out.EvidenceHealthPort;
import com.prudence.posture.application.port.out.SystemInventoryPort;
import com.prudence.posture.domain.Posture;
import com.prudence.posture.domain.Posture.PostureStatus;
import com.prudence.posture.domain.Posture.SystemPosture;
import org.springframework.stereotype.Service;

import java.util.List;

/// Computes estate posture by joining the registry's governance facts with Ernest's evidence
/// health. The rules are deliberately conservative: a system is GREEN only when it is both
/// approved to run *and* backed by an intact, reachable evidence record.
@Service
public class PostureService implements PostureQuery {

    private final SystemInventoryPort inventory;
    private final EvidenceHealthPort evidence;

    public PostureService(SystemInventoryPort inventory, EvidenceHealthPort evidence) {
        this.inventory = inventory;
        this.evidence = evidence;
    }

    @Override
    public Posture compute() {
        EvidenceHealthPort.EvidenceHealth health = evidence.check();

        List<SystemPosture> systems = inventory.all().stream()
                .map(s -> score(s, health.healthy()))
                .toList();

        PostureStatus overall = systems.stream()
                .map(SystemPosture::status)
                .reduce(PostureStatus.GREEN, PostureService::worst);

        return new Posture(overall, health.healthy(), health.detail(), systems);
    }

    private SystemPosture score(SystemInventoryPort.GovernedSystem s, boolean evidenceHealthy) {
        PostureStatus status;
        String detail;

        switch (s.approvalStatus()) {
            case BLOCKED -> { status = PostureStatus.RED; detail = "Prohibited tier — blocked from use."; }
            case REJECTED -> { status = PostureStatus.RED; detail = "Rejected by governance review."; }
            case DRAFT, PENDING_APPROVAL -> { status = PostureStatus.AMBER; detail = "Awaiting governance approval."; }
            case APPROVED -> {
                if (evidenceHealthy) { status = PostureStatus.GREEN; detail = "Approved and attested by Ernest."; }
                else { status = PostureStatus.AMBER; detail = "Approved, but evidence is unavailable."; }
            }
            default -> { status = PostureStatus.AMBER; detail = "Unknown state."; }
        }

        return new SystemPosture(s.id(), s.name(), s.riskTier().name(), s.approvalStatus().name(), status, detail);
    }

    /// Estate roll-up: the worst colour wins (one RED makes the estate RED).
    private static PostureStatus worst(PostureStatus a, PostureStatus b) {
        if (a == PostureStatus.RED || b == PostureStatus.RED) return PostureStatus.RED;
        if (a == PostureStatus.AMBER || b == PostureStatus.AMBER) return PostureStatus.AMBER;
        return PostureStatus.GREEN;
    }
}
