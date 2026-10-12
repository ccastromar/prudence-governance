package com.prudence.registry.adapter.out.ernest;

import com.prudence.config.IntegrationProperties;
import com.prudence.evidence.adapter.out.ernest.ErnestSealer;
import com.prudence.registry.application.port.out.ReviewSealPort;
import com.prudence.registry.domain.ReviewEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/// ACL adapter: seals a governance review event into Ernest (model "prudence-reviews"),
/// qualify-sealed (ADR-002 B2) when a QTSP is configured. Fail-soft — review audit is never
/// blocked by the evidence plane.
@Component
public class ReviewSealAdapter implements ReviewSealPort {

    private static final Logger log = LoggerFactory.getLogger(ReviewSealAdapter.class);
    private static final String MODEL_ID = "prudence-reviews";

    private final RestClient ernest;
    private final ErnestSealer sealer;
    private final boolean enabled;
    private volatile boolean modelEnsured = false;

    public ReviewSealAdapter(IntegrationProperties props) {
        String apiKey = props.ernest().apiKey();
        RestClient.Builder b = RestClient.builder().baseUrl(props.ernest().baseUrl());
        if (apiKey != null && !apiKey.isBlank()) b.defaultHeader("X-Ernest-Api-Key", apiKey);
        this.ernest = b.build();

        String cscUrl = props.qtsp() != null ? props.qtsp().cscUrl() : null;
        boolean canSeal = apiKey != null && !apiKey.isBlank() && cscUrl != null && !cscUrl.isBlank();
        this.sealer = canSeal ? new ErnestSealer(cscUrl) : null;
        this.enabled = canSeal;
    }

    @Override
    public SealResult seal(ReviewEvent e) {
        if (!enabled) return new SealResult(false, null);
        try {
            ensureModel();

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("aiSystemId", e.aiSystemId());
            content.put("action", e.action());
            content.put("reviewer", e.reviewer());
            content.put("reason", e.reason());
            content.put("at", e.at());
            content.put("previousStatus", e.previousStatus());
            content.put("newStatus", e.newStatus());
            String contentHash = ErnestSealer.sha256Canonical(content);

            Map<String, Object> metadata = new LinkedHashMap<>(content);
            metadata.put("stage", "governance_review");
            metadata.put("reviewId", e.id());

            Map<String, Object> signed = new LinkedHashMap<>();
            signed.put("type", "inference");
            signed.put("modelId", MODEL_ID);
            signed.put("inferenceId", e.id());
            signed.put("inputHash", contentHash);
            signed.put("outputHash", contentHash);
            signed.put("metadata", metadata);

            Map<String, Object> body = new LinkedHashMap<>(signed);
            body.remove("type");
            body.put("signature", sealer.seal(signed));

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = ernest.post().uri("/api/inferences").body(body).retrieve().body(Map.class);
            return new SealResult(true, extractBlockIndex(resp));
        } catch (Exception ex) {
            log.warn("could not seal review event {} in Ernest: {}", e.id(), ex.getMessage());
            return new SealResult(false, null);
        }
    }

    private void ensureModel() {
        if (modelEnsured) return;
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("type", "model_registration");
        signed.put("modelId", MODEL_ID);
        signed.put("modelName", "Prudence governance reviews");
        signed.put("version", "1");
        signed.put("modelHash", ErnestSealer.sha256Canonical(MODEL_ID));
        signed.put("gitCommit", "0000000");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", MODEL_ID);
        body.put("modelName", "Prudence governance reviews");
        body.put("version", "1");
        body.put("mlflow", Map.of("modelHash", ErnestSealer.sha256Canonical(MODEL_ID), "gitCommit", "0000000"));
        body.put("signature", sealer.seal(signed));
        try {
            ernest.post().uri("/api/models").body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 400) throw e;
        }
        modelEnsured = true;
    }

    private static Long extractBlockIndex(Map<String, Object> resp) {
        if (resp == null) return null;
        if (resp.get("blockIndex") instanceof Number n) return n.longValue();
        if (resp.get("blockchain") instanceof Map<?, ?> bc && bc.get("index") instanceof Number n) return n.longValue();
        return null;
    }
}
