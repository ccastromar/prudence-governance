package com.prudence.registry.domain;

/// An append-only audit record of a governance review decision (approve/reject): who decided,
/// when, why, and the state transition. The record is sealed in Ernest so the audit trail is
/// itself tamper-evident. `sealed`/`blockIndex` capture where it was sealed.
public record ReviewEvent(
        String id,
        String aiSystemId,
        String action,          // APPROVE | REJECT
        String reviewer,
        String reason,          // nullable (reject reason; approve has none)
        String at,              // ISO-8601 instant
        String previousStatus,
        String newStatus,
        boolean sealed,
        Long blockIndex) {}
