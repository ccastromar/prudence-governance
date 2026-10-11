package com.prudence.posture.domain;

import java.util.List;

/// The compliance posture of the AI estate: a traffic-light per registered system plus an
/// estate-wide roll-up. GREEN = approved and provably attested; AMBER = needs attention
/// (awaiting approval, or approved but evidence unavailable); RED = must not run
/// (prohibited, blocked or rejected).
public record Posture(PostureStatus overall, boolean evidenceHealthy, String evidenceDetail,
                      List<SystemPosture> systems) {

    public enum PostureStatus { GREEN, AMBER, RED }

    public record SystemPosture(String id, String name, String riskTier, String approvalStatus,
                                PostureStatus status, String detail) {}
}
