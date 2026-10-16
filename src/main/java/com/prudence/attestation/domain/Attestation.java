package com.prudence.attestation.domain;

import java.util.List;

/// A point-in-time, tamper-evident attestation of the AI estate — the governance report a
/// regulator or board receives. The report (id, time, estate roll-up, per-system facts) is
/// hashed and the hash is sealed in Ernest, so the report itself is tamper-evident and
/// carries a qualified seal. `evidence` records where/whether it was sealed.
///
/// ADR-000 "reporting & attestation": the loop closes — the governance report is proved by
/// the same evidence plane that proves executions.
public record Attestation(
    String id,
    String generatedAt,
    String overallPosture,
    String contentHash,
    List<Entry> systems,
    Evidence evidence) {

  /// One registered AI system as it stood at attestation time.
  public record Entry(
      String aiSystemId,
      String name,
      String riskTier,
      String approvalStatus,
      String posture,
      String detail) {}

  /// Where the report hash was recorded. `delivery` is PENDING until Ernest has it (retried
  /// in the background), DELIVERED once recorded, DEAD if Ernest rejected it for good.
  /// `sealed` means qualified-sealed (QTSP): a DELIVERED report may be recorded unsealed.
  public record Evidence(
      boolean sealed, Long blockIndex, String modelId, String detail, String delivery) {}
}
