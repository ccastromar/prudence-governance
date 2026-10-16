package com.prudence.compliance.adapter.in.web.dto;

import com.prudence.compliance.application.port.in.ComplianceControls;
import jakarta.validation.constraints.NotBlank;

public record SatisfyComplianceControlRequest(
    @NotBlank String evidenceUri,
    @NotBlank String evidenceSha256,
    @NotBlank String evidenceVersion,
    @NotBlank String evidenceOwner,
    String datasetVersion,
    String metricsByGroup,
    String approver,
    String signedAttestation,
    String ernestReceipt,
    String expiresAt) {
  public ComplianceControls.Evidence toEvidence() {
    return new ComplianceControls.Evidence(
        evidenceUri,
        evidenceSha256,
        evidenceVersion,
        evidenceOwner,
        datasetVersion,
        metricsByGroup,
        approver,
        signedAttestation,
        ernestReceipt,
        expiresAt);
  }
}
