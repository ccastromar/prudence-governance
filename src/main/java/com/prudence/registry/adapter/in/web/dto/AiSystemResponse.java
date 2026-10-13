package com.prudence.registry.adapter.in.web.dto;

import com.prudence.registry.domain.AiSystem;

public record AiSystemResponse(
        String id,
        String name,
        String intendedPurpose,
        String ownerName,
        String ownerEmail,
        String riskTier,
        boolean annexIII,
        String approvalStatus,
        String regulatoryRole,
        String systemVersion,
        String modelHash,
        boolean annexI,
        String operatingGeography,
        String affectedPopulation,
        String classificationJustification,
        java.util.List<String> obligations,
        String assessedAt,
        String approvedAt,
        String approvalExpiresAt,
        String firstApprover,
        String firstApprovedAt,
        String renewalFirstApprover,
        String renewalFirstApprovedAt,
        boolean reviewDue) {

    public static AiSystemResponse from(AiSystem s) {
        return new AiSystemResponse(
                s.id().value(),
                s.name(),
                s.intendedPurpose().value(),
                s.owner().name(),
                s.owner().email(),
                s.riskTier().name(),
                s.classification().annexIII(),
                s.approvalStatus().name(),
                s.classification().regulatoryRole().name(), s.classification().systemVersion(),
                s.classification().modelHash(), s.classification().annexI(),
                s.classification().operatingGeography(), s.classification().affectedPopulation(),
                s.classification().justification(), s.classification().obligations(), s.classification().assessedAt(),
                s.approvedAt(), s.approvalExpiresAt(), s.firstApprover(), s.firstApprovedAt(),
                s.renewalFirstApprover(), s.renewalFirstApprovedAt(), s.reviewDue());
    }
}
