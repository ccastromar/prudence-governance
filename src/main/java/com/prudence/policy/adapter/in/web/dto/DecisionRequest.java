package com.prudence.policy.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record DecisionRequest(
    @NotBlank String subject,
    @NotBlank String pipeline,
    @NotBlank String aiSystemId,
    @NotBlank String action) {}
