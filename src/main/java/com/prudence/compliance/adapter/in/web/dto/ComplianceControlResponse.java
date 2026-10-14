package com.prudence.compliance.adapter.in.web.dto;

import com.prudence.compliance.adapter.out.persistence.ComplianceControlJpaEntity;
import com.prudence.compliance.domain.ComplianceControlStatus;

import java.time.Instant;

public record ComplianceControlResponse(
        String id, String profileId, String key, String title, String requirement, boolean blocking, String accepts,
        Integer expiresAfterDays, boolean expiresOnVersionChange, boolean blocksVersionChange, String evidenceSource,
        String status, String evidenceUri, String evidenceSha256, String evidenceVersion, String evidenceOwner,
        String evidenceDatasetVersion, String evidenceMetrics, String evidenceApprover, String evidenceAttestation, String ernestReceipt,
        String satisfiedAt, String expiresAt, String createdAt) {
    public static ComplianceControlResponse from(ComplianceControlJpaEntity control) {
        return from(control, null);
    }
    public static ComplianceControlResponse from(ComplianceControlJpaEntity control, String currentSystemVersion) {
        boolean expired = control.getStatus() == ComplianceControlStatus.SATISFIED && control.getExpiresAt() != null
                && !control.getExpiresAt().isBlank() && !Instant.parse(control.getExpiresAt()).isAfter(Instant.now());
        expired = expired || (control.getStatus() == ComplianceControlStatus.SATISFIED && control.isExpiresOnVersionChange()
                && currentSystemVersion != null && !currentSystemVersion.equals(control.getEvidenceVersion()));
        return new ComplianceControlResponse(control.getId(), control.getProfileId(), control.getControlKey(), control.getTitle(),
                control.getRequirement(), control.isBlocking(), control.getAccepts(), control.getExpiresAfterDays(), control.isExpiresOnVersionChange(), control.isBlocksVersionChange(), control.getEvidenceSource(), expired ? "EXPIRED" : control.getStatus().name(),
                control.getEvidenceUri(), control.getEvidenceSha256(), control.getEvidenceVersion(), control.getEvidenceOwner(),
                control.getEvidenceDatasetVersion(), control.getEvidenceMetrics(), control.getEvidenceApprover(), control.getEvidenceAttestation(), control.getErnestReceipt(), control.getSatisfiedAt(), control.getExpiresAt(), control.getCreatedAt());
    }
}
