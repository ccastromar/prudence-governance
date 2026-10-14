package com.prudence.evidence.adapter.out.ernest;

import com.prudence.config.IntegrationProperties;
import com.prudence.policy.domain.DecisionContext;
import com.prudence.policy.domain.PolicyDecision;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// Driven adapter to Ernest. Records each governance decision as a tamper-evident provenance
/// block — and, when a QTSP is configured (ADR-002 B2), additionally qualify-seals it, so the control
/// plane proves its own decisions exactly as the runtime agents prove their steps. The AI
/// system's registry id is used as the Ernest model id, tying the governance record to the
/// system it governs (and letting posture read evidence back per system).
///
/// Called only by the evidence outbox worker, so recording never blocks or fails a decision.
/// A QTSP is optional for recording; without it, Ernest still receives an unsigned record.
@Component
public class ErnestEvidenceAdapter {

    private static final Logger log = LoggerFactory.getLogger(ErnestEvidenceAdapter.class);

    private final RestClient ernest;
    private final ErnestSealer sealer;
    private final boolean enabled;
    private final Set<String> registeredModels = ConcurrentHashMap.newKeySet();
    /// Non-null when decisions are queued on Ernest's event-ingestor instead of appended directly.
    private final RestClient ingestor;
    /// Non-null when decisions are published to Kafka (Ernest ADR-005).
    private final ErnestKafkaPublisher kafka;

    public ErnestEvidenceAdapter(IntegrationProperties props) {
        this.ernest = ErnestHttp.writeClient(props);

        String cscUrl = props.qtsp() != null ? props.qtsp().cscUrl() : null;
        boolean canSeal = cscUrl != null && !cscUrl.isBlank();
        this.sealer = canSeal ? new ErnestSealer(cscUrl) : null;
        this.enabled = props.ernest().baseUrl() != null && !props.ernest().baseUrl().isBlank();

        if (props.ernest().decisionsViaIngestor()) {
            String url = props.ernest().ingestorUrl();
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("prudence.ernest.decision-transport=ingestor needs prudence.ernest.ingestor-url");
            }
            if (sealer != null) {
                // The ingestor stores its own event envelope in the block, so a seal over the
                // direct block-data would not verify. Refuse rather than silently drop seals.
                throw new IllegalStateException("qualified seals (prudence.qtsp.csc-url) need decision-transport=direct");
            }
            this.ingestor = ErnestHttp.ingestorClient(url, props.ernest().ingestorKey());
            this.kafka = null;
            log.info("PDP decisions are queued on Ernest's event-ingestor at {}", url);
        } else if (props.ernest().decisionsViaKafka()) {
            String servers = props.ernest().kafkaBootstrapServers();
            String topic = props.ernest().kafkaTopic();
            if (servers == null || servers.isBlank() || topic == null || topic.isBlank()) {
                throw new IllegalStateException(
                        "prudence.ernest.decision-transport=kafka needs prudence.ernest.kafka-bootstrap-servers and kafka-topic");
            }
            if (sealer != null) {
                // Like the ingestor: the record is stored inside the ingestor's envelope.
                throw new IllegalStateException("qualified seals (prudence.qtsp.csc-url) need decision-transport=direct");
            }
            this.ingestor = null;
            IntegrationProperties.Ernest e = props.ernest();
            Map<String, Object> security = ErnestKafkaPublisher.securityConfig(e.kafkaSecurityProtocol(),
                    e.kafkaSaslMechanism(), e.kafkaSaslUsername(), e.kafkaSaslPassword(),
                    e.kafkaTruststoreLocation(), e.kafkaTruststorePassword(), e.kafkaProperties());
            this.kafka = new ErnestKafkaPublisher(servers, topic, security);
            log.info("PDP decisions are published to Kafka topic {} at {} ({})", topic, servers, security.get("security.protocol"));
        } else {
            this.ingestor = null;
            this.kafka = null;
        }
    }

    @PreDestroy
    void close() {
        if (kafka != null) kafka.close();
    }

    /// Delivers one decision under a caller-supplied, stable `inferenceId` (the outbox event
    /// id), so a retried delivery carries the same id and can be reconciled. Failures propagate
    /// for the caller to classify with [ErnestHttp#classify].
    public void publishDecision(String inferenceId, DecisionContext context, PolicyDecision decision) {
        String verdict = verdictOf(decision);
        String reason = reasonOf(decision);

        if (kafka != null) {
            Exception failure = kafka.send(List.of(kafkaEvent(inferenceId, context, verdict, reason))).get(0);
            if (failure != null) throw failure instanceof RuntimeException re ? re : new IllegalStateException(failure);
            return;
        }
        if (ingestor != null) {
            enqueueDecision(inferenceId, context, verdict, reason);
            log.info("governance decision queued on Ernest's ingestor: system={} -> {}", context.aiSystemId(), verdict);
            return;
        }
        if (!enabled) {
            throw new IllegalStateException("Ernest evidence endpoint is not configured");
        }

        ensureModel(context.aiSystemId());
        sealAndSubmitDecision(inferenceId, context, verdict, reason);
        log.info("governance decision delivered to Ernest: system={} -> {}", context.aiSystemId(), verdict);
    }

    /// Register an Ernest model for this AI system once (idempotent across the process).
    private void ensureModel(String aiSystemId) {
        if (registeredModels.contains(aiSystemId)) return;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "prudence-pdp");
        metadata.put("aiSystemId", aiSystemId);

        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("type", "model_registration");
        signed.put("modelId", aiSystemId);
        signed.put("modelName", "AI system " + aiSystemId);
        signed.put("version", "1");
        signed.put("modelHash", sha256Hex(aiSystemId));
        signed.put("gitCommit", "0000000");
        signed.put("metadata", metadata);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", aiSystemId);
        body.put("modelName", "AI system " + aiSystemId);
        body.put("version", "1");
        body.put("mlflow", Map.of("modelHash", sha256Hex(aiSystemId), "gitCommit", "0000000"));
        body.put("metadata", metadata);
        if (sealer != null) body.put("signature", sealer.seal(signed));

        try {
            ernest.post().uri("/api/models").body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            // A model for this system may already exist from a prior run — fine, the
            // registration block is on-chain. Any other rejection must surface.
            if (!ErnestHttp.isAlreadyRegistered(e)) throw e;
            log.debug("model {} already registered", aiSystemId);
        }
        registeredModels.add(aiSystemId);
    }

    /// One decision waiting to be published, under its stable outbox id.
    public record DecisionToPublish(String inferenceId, DecisionContext context, PolicyDecision decision) {}

    /// Ernest answered 404 to the batch endpoint once: it predates it, so publish one by one.
    private volatile boolean batchUnsupported;

    /// Whether [#publishDecisions] sends real batches (direct transport, Ernest supports it).
    public boolean batchCapable() {
        return kafka != null || (ingestor == null && !batchUnsupported);
    }

    /// Publishes several decisions in one POST /api/inferences/batch call. Returns one entry
    /// per input, in order: null when delivered, otherwise the failure — an HTTP status error
    /// carrying the status Ernest reported for that item, so [ErnestHttp#classify] treats it
    /// exactly as it would a single submission. Falls back to one-by-one when batching is not
    /// available (ingestor transport, or an Ernest without the batch endpoint).
    public List<Exception> publishDecisions(List<DecisionToPublish> items) {
        if (kafka != null) {
            return kafka.send(items.stream()
                    .map(i -> kafkaEvent(i.inferenceId(), i.context(), verdictOf(i.decision()), reasonOf(i.decision())))
                    .toList());
        }
        List<Exception> outcomes = new ArrayList<>(Collections.nCopies(items.size(), (Exception) null));
        if (!batchCapable()) {
            for (int i = 0; i < items.size(); i++) outcomes.set(i, publishOne(items.get(i)));
            return outcomes;
        }
        if (!enabled) throw new IllegalStateException("Ernest evidence endpoint is not configured");

        // Register each AI system's model once; a failure there fails only that system's items.
        Map<String, Exception> modelFailures = new HashMap<>();
        for (DecisionToPublish item : items) {
            String system = item.context().aiSystemId();
            if (modelFailures.containsKey(system)) continue;
            try {
                ensureModel(system);
                modelFailures.put(system, null);
            } catch (Exception e) {
                modelFailures.put(system, e);
            }
        }
        List<Map<String, Object>> bodies = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            DecisionToPublish item = items.get(i);
            Exception modelFailure = modelFailures.get(item.context().aiSystemId());
            if (modelFailure != null) {
                outcomes.set(i, modelFailure);
                continue;
            }
            bodies.add(inferenceBody(item.inferenceId(), item.context(), verdictOf(item.decision()), reasonOf(item.decision())));
            positions.add(i);
        }
        if (bodies.isEmpty()) return outcomes;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = ernest.post().uri("/api/inferences/batch").body(Map.of("items", bodies)).retrieve().body(Map.class);
            List<?> results = resp != null && resp.get("results") instanceof List<?> l ? l : List.of();
            for (int j = 0; j < positions.size(); j++) {
                Map<?, ?> r = j < results.size() && results.get(j) instanceof Map<?, ?> m ? m : Map.of();
                int status = r.get("status") instanceof Number n ? n.intValue() : 500;
                // 200 chained/duplicate, 202 accepted pending its batch (Ernest ADR-004): both delivered.
                if (status != 200 && status != 202) {
                    Object err = r.get("error");
                    String error = err == null ? "no result reported" : err.toString();
                    outcomes.set(positions.get(j), new RestClientResponseException(error, status, "", null, null, null));
                }
            }
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                batchUnsupported = true;
                log.info("Ernest has no /api/inferences/batch; publishing decisions one by one");
                for (int i : positions) outcomes.set(i, publishOne(items.get(i)));
            } else {
                for (int i : positions) outcomes.set(i, e);
            }
        } catch (Exception e) {
            for (int i : positions) outcomes.set(i, e);
        }
        return outcomes;
    }

    private Exception publishOne(DecisionToPublish item) {
        try {
            publishDecision(item.inferenceId(), item.context(), item.decision());
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private static String verdictOf(PolicyDecision decision) {
        return switch (decision) {
            case PolicyDecision.Allow ignored -> "ALLOW";
            case PolicyDecision.ApprovalRequired ignored -> "APPROVAL_REQUIRED";
            case PolicyDecision.Deny ignored -> "DENY";
        };
    }

    private static String reasonOf(PolicyDecision decision) {
        return switch (decision) {
            case PolicyDecision.ApprovalRequired ar -> ar.reason();
            case PolicyDecision.Deny d -> d.reason();
            case PolicyDecision.Allow ignored -> null;
        };
    }

    /// Ingestor transport: the decision id is the source event id, so the ingestor discards a
    /// re-sent decision as a duplicate. 202 means "durably queued", not yet "appended"; no
    /// model registration is needed (the ingestor maintains the model record itself).
    private void enqueueDecision(String inferenceId, DecisionContext ctx, String verdict, String reason) {
        Map<String, Object> body = eventBody(inferenceId, ctx, verdict, reason);
        ingestor.post().uri("/events")
                .header("X-Ernest-Event-Source", "prudence-pdp")
                .header("X-Ernest-Event-Type", "inference.logged")
                .header("X-Ernest-Source-Event-Id", inferenceId)
                .body(body).retrieve().toBodilessEntity();
    }

    /// The event body Ernest's ingestor receives for a decision, over HTTP or Kafka alike.
    private Map<String, Object> eventBody(String inferenceId, DecisionContext ctx, String verdict, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", ctx.aiSystemId());
        body.put("inferenceId", inferenceId);
        body.put("inputHash", sha256Hex(ctx.subject() + "|" + ctx.pipeline() + "|" + ctx.action()));
        body.put("outputHash", sha256Hex(verdict + "|" + (reason == null ? "" : reason)));
        body.put("metadata", decisionMetadata(ctx, verdict, reason));
        return body;
    }

    /// Kafka record for a decision: keyed by AI system (per-system order), deduped by its id.
    private ErnestKafkaPublisher.Event kafkaEvent(String inferenceId, DecisionContext ctx, String verdict, String reason) {
        return new ErnestKafkaPublisher.Event(ctx.aiSystemId(), inferenceId, eventBody(inferenceId, ctx, verdict, reason));
    }

    private void sealAndSubmitDecision(String inferenceId, DecisionContext ctx, String verdict, String reason) {
        ernest.post().uri("/api/inferences").body(inferenceBody(inferenceId, ctx, verdict, reason)).retrieve().toBodilessEntity();
    }

    /// The submission for one decision (qualified-sealed when a QTSP is configured).
    private Map<String, Object> inferenceBody(String inferenceId, DecisionContext ctx, String verdict, String reason) {
        String inputHash = sha256Hex(ctx.subject() + "|" + ctx.pipeline() + "|" + ctx.action());
        String outputHash = sha256Hex(verdict + "|" + (reason == null ? "" : reason));

        Map<String, Object> metadata = decisionMetadata(ctx, verdict, reason);

        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("type", "inference");
        signed.put("modelId", ctx.aiSystemId());
        signed.put("inferenceId", inferenceId);
        signed.put("inputHash", inputHash);
        signed.put("outputHash", outputHash);
        signed.put("metadata", metadata);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", ctx.aiSystemId());
        body.put("inferenceId", inferenceId);
        body.put("inputHash", inputHash);
        body.put("outputHash", outputHash);
        body.put("metadata", metadata);
        if (sealer != null) body.put("signature", sealer.seal(signed));

        return body;
    }

    /// What a decision record says, identical on both transports.
    private static Map<String, Object> decisionMetadata(DecisionContext ctx, String verdict, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stage", "governance_decision");
        metadata.put("subject", ctx.subject());
        metadata.put("pipeline", ctx.pipeline());
        metadata.put("action", ctx.action());
        metadata.put("aiSystemId", ctx.aiSystemId());
        metadata.put("policyVersion", ctx.policyVersion());
        metadata.put("policyHash", ctx.policyHash());
        metadata.put("systemVersion", ctx.systemVersion());
        metadata.put("modelHash", ctx.modelHash());
        if (ctx.causationId() != null) metadata.put("causationId", ctx.causationId());
        if (ctx.correlationId() != null) metadata.put("correlationId", ctx.correlationId());
        if (ctx.systemSequence() != null) metadata.put("systemSequence", ctx.systemSequence());
        metadata.put("decision", verdict);
        if (reason != null) metadata.put("reason", reason);
        return metadata;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
