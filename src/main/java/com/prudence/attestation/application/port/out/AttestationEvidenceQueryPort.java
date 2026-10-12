package com.prudence.attestation.application.port.out;

/// Driven port: ask the evidence plane (Ernest) whether a given content hash is sealed under
/// a model, and whether that model's chain verifies. Used to independently confirm an
/// attestation rather than trusting Prudence's own stored flag.
public interface AttestationEvidenceQueryPort {
    Confirmation confirm(String modelId, String contentHash);

    /// `reachable=false` means the evidence plane could not be consulted (fail-closed:
    /// unknown is not confirmed).
    record Confirmation(boolean reachable, boolean present, boolean chainValid, Long blockIndex) {}
}
