package com.prudence.posture.application.port.out;

/// ACL over the evidence plane (Ernest): the one fact posture needs is whether the
/// tamper-evident record is intact and reachable right now.
public interface EvidenceHealthPort {

    /// True when Ernest's hashchain verifies and is reachable; false when it is broken,
    /// empty or unreachable (fail-closed — unknown evidence is not healthy evidence).
    EvidenceHealth check();

    /// Whether a specific AI system has intact evidence in Ernest (a provenance record under
    /// the system's id). Lets posture tell "approved and attested" from "approved but nothing
    /// has been proved yet" — a policy-drift signal. Fail-closed on any error.
    boolean hasEvidence(String aiSystemId);

    record EvidenceHealth(boolean healthy, String detail) {}
}
