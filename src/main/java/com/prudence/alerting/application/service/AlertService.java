package com.prudence.alerting.application.service;

import com.prudence.alerting.application.port.in.AlertQueries;
import com.prudence.alerting.application.port.in.RaiseAlert;
import com.prudence.alerting.application.port.out.AlertStore;
import com.prudence.alerting.domain.AlertKind;
import com.prudence.evidence.delivery.DeliveryFailure;
import com.prudence.evidence.delivery.DeliveryListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/// Raises alerts (stored, then emailed by the delivery worker) and turns delivery problems —
/// dead-lettered evidence, a target refusing the configuration — into operational alerts.
@Service
public class AlertService implements RaiseAlert, AlertQueries, DeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertStore store;
    private final List<String> governance;
    private final List<String> operations;
    private final String consoleUrl;

    public AlertService(AlertStore store,
                        @Value("${prudence.alerts.governance-recipients:}") String governance,
                        @Value("${prudence.alerts.operations-recipients:}") String operations,
                        @Value("${prudence.alerts.console-url:}") String consoleUrl) {
        this.store = store;
        this.governance = split(governance);
        this.operations = split(operations);
        this.consoleUrl = consoleUrl == null ? "" : consoleUrl.strip();
    }

    @Override
    @Transactional
    public void raise(AlertRequest r) {
        if (store.exists(r.dedupKey())) return; // already alerted
        // Recipients are resolved now and stored, so every retry goes to the same people.
        Set<String> to = new LinkedHashSet<>(r.audience() == Audience.GOVERNANCE ? governance : operations);
        if (r.extraRecipients() != null) {
            r.extraRecipients().stream().filter(a -> a != null && !a.isBlank()).map(String::strip).forEach(to::add);
        }
        String body = r.body() + (consoleUrl.isEmpty() ? "" : "\n\nPrudence console: " + consoleUrl);
        store.add(UUID.randomUUID().toString(), r.kind(), r.dedupKey(), r.aiSystemId(), List.copyOf(to),
                "[Prudence] " + r.subject(), body);
        log.info("alert raised: {} ({}) → {}", r.kind(), r.dedupKey(), to);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertView> recent(int limit) {
        return store.recent(Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public void deadLettered(String target, String recordId, DeliveryFailure failure, String error) {
        safely(() -> raise(new AlertRequest(AlertKind.EVIDENCE_DEAD_LETTERED, "DEAD:" + target + ":" + recordId,
                Audience.OPERATIONS, List.of(), null,
                "Governance evidence could not be delivered (" + target + ")",
                "A " + target + " record (" + recordId + ") was dead-lettered "
                        + (failure == DeliveryFailure.PERMANENT ? "because the receiver rejected it" : "after exhausting its retries")
                        + " and will not be retried automatically. That evidence is missing until someone "
                        + "investigates and re-drives it; posture reports the affected system as RED.\n\nLast error: " + error)));
    }

    @Override
    public void configurationFailing(String target, String error) {
        // Once per target per day: a long outage is reminded daily, not per record.
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        safely(() -> raise(new AlertRequest(AlertKind.DELIVERY_CONFIGURATION, "CONFIG:" + target + ":" + day,
                Audience.OPERATIONS, List.of(), null,
                "Evidence delivery is refusing Prudence's configuration (" + target + ")",
                "Delivery of " + target + " records is failing with a configuration error (credentials or "
                        + "endpoint). Nothing is lost: records are queued and retried every few minutes until the "
                        + "configuration is fixed.\n\nError: " + error)));
    }

    /// Alerting must never break the delivery it reports on.
    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("could not raise alert: {}", e.getMessage());
        }
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
