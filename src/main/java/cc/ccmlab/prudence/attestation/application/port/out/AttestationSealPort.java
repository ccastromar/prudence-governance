package cc.ccmlab.prudence.attestation.application.port.out;

import java.util.Map;

/// Driven port: record an attestation's content hash in the evidence plane (Ernest) under the
/// attestation id, returning where it landed. Throws on failure so the evidence delivery
/// worker can classify and retry it; report generation itself never waits on a retry.
public interface AttestationSealPort {
  Receipt seal(String attestationId, String contentHash, Map<String, Object> summary)
      throws Exception;

  /// `qualified`: carries a QTSP seal. `detail`: human-readable account of the record.
  record Receipt(boolean qualified, Long blockIndex, String detail) {}
}
