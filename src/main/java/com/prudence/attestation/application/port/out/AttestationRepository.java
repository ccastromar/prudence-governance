package com.prudence.attestation.application.port.out;

import com.prudence.attestation.domain.Attestation;

import java.util.List;
import java.util.Optional;

/// Driven port: durable storage of issued attestations (the exact sealed report is kept so
/// its hash can be reproduced and verified later).
public interface AttestationRepository {
    void save(Attestation attestation, String reportJson);
    List<Stored> findAllNewestFirst();
    Optional<Stored> findById(String id);

    record Stored(Attestation attestation, String reportJson) {}
}
