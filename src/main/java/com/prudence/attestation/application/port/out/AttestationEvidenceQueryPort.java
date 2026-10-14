package com.prudence.attestation.application.port.out;

/// Driven port: ask the evidence plane (Ernest) whether a given content hash is sealed under
/// a model, and whether that model's chain verifies. Used to independently confirm an
/// attestation rather than trusting Prudence's own stored flag. `eventId` is the id the
/// attestation was recorded under, so its inclusion proof can be fetched and checked
/// directly (Ernest ADR-004) instead of scanning the model's whole history.
public interface AttestationEvidenceQueryPort {
  Confirmation confirm(String modelId, String eventId, String contentHash);

  /// `reachable=false` means the evidence plane could not be consulted (fail-closed:
  /// unknown is not confirmed). `pending`: Ernest accepted the record but has not chained it
  /// yet (ADR-004 batching) — neither confirmed nor missing.
  record Confirmation(
      boolean reachable, boolean present, boolean chainValid, Long blockIndex, boolean pending) {
    public Confirmation(boolean reachable, boolean present, boolean chainValid, Long blockIndex) {
      this(reachable, present, chainValid, blockIndex, false);
    }
  }
}
