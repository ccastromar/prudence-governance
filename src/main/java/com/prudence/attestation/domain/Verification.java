package com.prudence.attestation.domain;

/// The result of independently verifying an attestation: recompute the stored report's hash
/// and confirm that hash is actually recorded in Ernest with a verifying chain. `sealed`
/// additionally says whether that record carries a qualified (QTSP) seal.
///
/// verdict:
///   VERIFIED    — report hash matches and is sealed in an intact Ernest chain
///   ALTERED     — the stored report no longer hashes to the attested content hash
///   PENDING     — hash matches; the report is still queued for delivery to Ernest
///   UNSEALED    — the report never reached Ernest (delivery dead-lettered)
///   UNCONFIRMED — hash matches but Ernest could not confirm the seal (unreachable / chain
///                 broken / hash not found)
public record Verification(
        String attestationId,
        boolean hashMatches,
        String recomputedHash,
        String storedHash,
        boolean sealed,
        Long blockIndex,
        boolean ernestConfirmed,
        boolean chainValid,
        String verdict,
        String detail) {}
