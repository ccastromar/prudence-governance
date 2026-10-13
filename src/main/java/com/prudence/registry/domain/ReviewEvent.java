package com.prudence.registry.domain;

/// An append-only audit record of a governance review decision (approve/reject): who decided,
/// when, why, and the state transition. The record is sealed in Ernest so the audit trail is
/// itself tamper-evident. The decision fields are immutable; only the evidence receipt
/// (`evidence`, `sealed`, `blockIndex`) is filled in once Ernest has recorded the event.
public record ReviewEvent(
        String id,
        String aiSystemId,
        String action,          // APPROVE | FIRST_APPROVAL | REJECT
        String reviewer,
        String reason,          // nullable (reject reason; approve has none)
        String at,              // ISO-8601 instant
        String previousStatus,
        String newStatus,
        String evidence,        // PENDING | DELIVERED | DEAD — delivery to Ernest
        boolean sealed,         // qualified-sealed (QTSP); a DELIVERED event may be recorded unsealed
        Long blockIndex) {}
