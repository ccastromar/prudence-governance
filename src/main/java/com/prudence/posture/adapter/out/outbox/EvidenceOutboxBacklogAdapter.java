package com.prudence.posture.adapter.out.outbox;

import com.prudence.evidence.delivery.Delivery;
import com.prudence.evidence.adapter.out.persistence.EvidenceOutboxJpaRepository;
import com.prudence.incident.adapter.out.persistence.IncidentEventJpaRepository;
import com.prudence.posture.application.port.out.EvidenceDeliveryPort;
import com.prudence.registry.adapter.out.persistence.ReviewEventJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// ACL adapter: projects undelivered governance evidence — PDP decisions in the outbox, review
/// events and incident timeline events — down to a per-system delivery backlog. (Attestations are estate-wide, not
/// per system; their delivery state is reported by attestation verification instead.)
@Component
public class EvidenceOutboxBacklogAdapter implements EvidenceDeliveryPort {

    private final EvidenceOutboxJpaRepository outbox;
    private final ReviewEventJpaRepository reviews;
    private final IncidentEventJpaRepository incidentEvents;
    private final Duration overdueAfter;

    public EvidenceOutboxBacklogAdapter(EvidenceOutboxJpaRepository outbox, ReviewEventJpaRepository reviews,
                                        IncidentEventJpaRepository incidentEvents,
                                        @Value("${prudence.evidence.overdue-after-minutes:15}") long overdueAfterMinutes) {
        this.outbox = outbox;
        this.reviews = reviews;
        this.incidentEvents = incidentEvents;
        this.overdueAfter = Duration.ofMinutes(overdueAfterMinutes);
    }

    @Override
    public Map<String, Backlog> undeliveredBySystem() {
        Instant cutoff = Instant.now().minus(overdueAfter);
        Map<String, Backlog> backlog = new HashMap<>();
        merge(backlog, outbox.undeliveredBySystem(Delivery.stamp(cutoff)));
        // Review times are Instant.toString(); compare against the same format.
        merge(backlog, reviews.undeliveredBySystem(cutoff.toString()));
        merge(backlog, incidentEvents.undeliveredBySystem(cutoff.toString()));
        return backlog;
    }

    private static void merge(Map<String, Backlog> backlog, List<Object[]> rows) {
        for (Object[] row : rows) {
            long dead = ((Number) row[1]).longValue();
            long overdue = ((Number) row[2]).longValue();
            if (dead == 0 && overdue == 0) continue;
            backlog.merge((String) row[0], new Backlog(dead, overdue),
                    (a, b) -> new Backlog(a.dead() + b.dead(), a.overdue() + b.overdue()));
        }
    }
}
