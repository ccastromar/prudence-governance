package com.prudence.compliance.adapter.in.web.dto;

import com.prudence.compliance.adapter.out.persistence.ComplianceControlJpaEntity;
import com.prudence.compliance.domain.ComplianceControlStatus;

import java.time.Instant;

public record ComplianceControlResponse(
        String id, String profileId, String key, String title, String requirement, boolean blocking,
        String status, String evidenceUri, String evidenceSha256, String evidenceVersion, String evidenceOwner,
        String satisfiedAt, String expiresAt, String createdAt) {
    public static ComplianceControlResponse from(ComplianceControlJpaEntity control) {
        boolean expired = control.getStatus() == ComplianceControlStatus.SATISFIED && control.getExpiresAt() != null
                && !control.getExpiresAt().isBlank() && !Instant.parse(control.getExpiresAt()).isAfter(Instant.now());
        return new ComplianceControlResponse(control.getId(), control.getProfileId(), control.getControlKey(), control.getTitle(),
                control.getRequirement(), control.isBlocking(), expired ? "EXPIRED" : control.getStatus().name(),
                control.getEvidenceUri(), control.getEvidenceSha256(), control.getEvidenceVersion(), control.getEvidenceOwner(),
                control.getSatisfiedAt(), control.getExpiresAt(), control.getCreatedAt());
    }
}
