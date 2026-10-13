package com.prudence.alerting.adapter.out.persistence;

import com.prudence.alerting.application.port.in.AlertQueries.AlertView;
import com.prudence.alerting.application.port.out.AlertStore;
import com.prudence.alerting.domain.AlertKind;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AlertStoreAdapter implements AlertStore {

    private final AlertJpaRepository jpa;

    public AlertStoreAdapter(AlertJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean exists(String dedupKey) {
        return jpa.existsByDedupKey(dedupKey);
    }

    @Override
    public void add(String id, AlertKind kind, String dedupKey, String aiSystemId, List<String> recipients,
                    String subject, String body) {
        jpa.save(new AlertJpaEntity(id, kind, dedupKey, aiSystemId, String.join(",", recipients), subject, body));
    }

    @Override
    public List<AlertView> recent(int limit) {
        return jpa.findAllByOrderByCreatedAtDesc(Limit.of(limit)).stream()
                .map(e -> new AlertView(e.getId(), e.getKind().name(), e.getAiSystemId(), e.getSubject(),
                        e.recipientList(), e.getCreatedAt(), e.getDelivery().status().name(), e.getDelivery().getLastError()))
                .toList();
    }
}
