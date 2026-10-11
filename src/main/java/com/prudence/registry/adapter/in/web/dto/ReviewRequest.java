package com.prudence.registry.adapter.in.web.dto;

/// Body of a reject (and, optionally, approve) review. The reviewer identity comes from the
/// authenticated principal, not the body — only the free-text reason is supplied here.
public record ReviewRequest(String reason) {}
