package com.prudence.attestation.application.port.out;

import com.prudence.attestation.domain.Attestation;

import java.util.Map;

/// Driven port: seal an attestation's content hash into the evidence plane (Ernest),
/// returning where it landed. Fail-soft — an unsealed result is returned when the evidence
/// plane is unavailable; it never throws into report generation.
public interface AttestationSealPort {
    Attestation.Evidence seal(String attestationId, String contentHash, Map<String, Object> summary);
}
