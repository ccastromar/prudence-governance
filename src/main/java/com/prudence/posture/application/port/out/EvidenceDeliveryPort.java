package com.prudence.posture.application.port.out;

import java.util.Map;

/// ACL over Prudence's own pending evidence (decisions, reviews, incidents) that has not reached Ernest.
/// Ernest's health says nothing about evidence that never arrived — this port does.
public interface EvidenceDeliveryPort {

    /// Undelivered evidence keyed by AI-system id; systems with no backlog are absent.
    Map<String, Backlog> undeliveredBySystem();

    /// `dead`: rejected by Ernest or out of retries — the evidence is missing until a human acts.
    /// `overdue`: still retrying, but older than the delivery grace period.
    record Backlog(long dead, long overdue) {}
}
