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
  /// yet (ADR-004 batching) — neither confirmed nor missing. `verifiable=false`: the store
  /// keeps custody only (ADR-001 C1) and cannot be asked at all — never confirmed, by design.
  record Confirmation(
      boolean reachable,
      boolean present,
      boolean chainValid,
      Long blockIndex,
      boolean pending,
      boolean verifiable) {
    public Confirmation(
        boolean reachable, boolean present, boolean chainValid, Long blockIndex, boolean pending) {
      this(reachable, present, chainValid, blockIndex, pending, true);
    }

    public Confirmation(boolean reachable, boolean present, boolean chainValid, Long blockIndex) {
      this(reachable, present, chainValid, blockIndex, false);
    }

    /// A custody-only store: nothing can be confirmed there.
    public static Confirmation notVerifiable() {
      return new Confirmation(false, false, false, null, false, false);
    }
  }
}
