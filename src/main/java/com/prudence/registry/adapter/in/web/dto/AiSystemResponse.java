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
        String approvalStatus) {

    public static AiSystemResponse from(AiSystem s) {
        return new AiSystemResponse(
                s.id().value(),
                s.name(),
                s.intendedPurpose().value(),
                s.owner().name(),
                s.owner().email(),
                s.riskTier().name(),
                s.riskTier().isAnnexIII(),
                s.approvalStatus().name());
    }
}
