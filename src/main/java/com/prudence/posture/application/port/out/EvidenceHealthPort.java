package com.prudence.posture.application.port.out;

/// ACL over the evidence plane (Ernest): the one fact posture needs is whether the
/// tamper-evident record is intact and reachable right now.
public interface EvidenceHealthPort {

    /// True when Ernest's hashchain verifies and is reachable; false when it is broken,
    /// empty or unreachable (fail-closed — unknown evidence is not healthy evidence).
    EvidenceHealth check();

    record EvidenceHealth(boolean healthy, String detail) {}
}
