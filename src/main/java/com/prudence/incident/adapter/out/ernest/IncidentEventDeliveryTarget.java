package com.prudence.incident.adapter.out.ernest;

import com.prudence.evidence.adapter.out.ernest.ErnestRecordWriter;
import com.prudence.evidence.delivery.Delivery;
import com.prudence.evidence.delivery.DeliveryTarget;
import com.prudence.incident.adapter.out.persistence.IncidentEventJpaEntity;
import com.prudence.incident.adapter.out.persistence.IncidentEventJpaRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/// Incident timeline events → Ernest (model "prudence-incidents"), under the event id — so
/// the register can prove when the organisation knew of an incident and when it reported.
@Component
public class IncidentEventDeliveryTarget implements DeliveryTarget<IncidentEventJpaEntity> {

    private static final String MODEL_ID = "prudence-incidents";

    private final IncidentEventJpaRepository events;
    private final ErnestRecordWriter ernest;

    public IncidentEventDeliveryTarget(IncidentEventJpaRepository events, ErnestRecordWriter ernest) {
        this.events = events;
        this.ernest = ernest;
    }

    @Override public String name() { return "incidents"; }
    @Override public List<IncidentEventJpaEntity> due(String now, int limit) { return events.due(now, Limit.of(limit)); }
    @Override public String id(IncidentEventJpaEntity e) { return e.getId(); }
    @Override public Delivery delivery(IncidentEventJpaEntity e) { return e.getDelivery(); }
    @Override public int claim(String id, String expected, String leaseUntil) { return events.claim(id, expected, leaseUntil); }
    @Override public Optional<IncidentEventJpaEntity> find(String id) { return events.findById(id); }
    @Override public List<IncidentEventJpaEntity> deadLettered(int limit) { return events.deadLettered(Limit.of(limit)); }
    @Override public String aiSystemId(IncidentEventJpaEntity e) { return e.getAiSystemId(); }
    @Override public void save(IncidentEventJpaEntity e) { events.save(e); }

    @Override
    public Consumer<IncidentEventJpaEntity> send(IncidentEventJpaEntity e) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("incidentId", e.getIncidentId());
        content.put("aiSystemId", e.getAiSystemId());
        content.put("type", e.getType());
        content.put("actor", e.getActor());
        content.put("at", e.getAt());
        content.put("details", e.getDetails());
        Map<String, Object> metadata = new LinkedHashMap<>(content);
        metadata.put("stage", "incident");
        metadata.put("eventId", e.getId());
        ErnestRecordWriter.Receipt receipt = ernest.record(MODEL_ID, "Prudence incident register", e.getId(), content, metadata);
        return record -> record.recordSeal(receipt.qualified(), receipt.blockIndex());
    }
}
