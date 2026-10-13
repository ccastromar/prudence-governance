package com.prudence.incident.domain;

/// An append-only entry in an incident's timeline (opened, triaged, reports filed, closed).
/// Each one is recorded in Ernest, so the register can prove when the organisation knew and
/// when it reported. `evidence` is the delivery state; `sealed` means qualified-sealed.
public record IncidentEvent(
        String id,
        String incidentId,
        String aiSystemId,
        String type,      // OPENED | TRIAGED | SYSTEM_SUSPENDED | INITIAL_REPORT | FINAL_REPORT | CLOSED
        String actor,
        String at,
        String details,
        String evidence,  // PENDING | DELIVERED | DEAD
        boolean sealed,
        Long blockIndex) {}
