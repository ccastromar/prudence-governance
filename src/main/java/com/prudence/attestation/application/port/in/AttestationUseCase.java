package com.prudence.attestation.application.port.in;

import com.prudence.attestation.domain.Attestation;

import java.util.List;
import java.util.Optional;

/// Driving port: generate a sealed attestation of the estate, and read prior ones.
public interface AttestationUseCase {

    /// Build the current estate report, seal its hash in Ernest, persist and return it.
    Attestation generate();

    /// Summaries of prior attestations (newest first).
    List<Summary> list();

    /// A full stored attestation (report + evidence) by id.
    Optional<Attestation> get(String id);

    record Summary(String id, String generatedAt, String overallPosture,
                   String contentHash, boolean sealed, Long blockIndex) {}
}
