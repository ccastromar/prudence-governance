package com.prudence.evidence.adapter.out.ernest;

import com.prudence.config.IntegrationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// Records one governance record in Ernest under a well-known model: registers the model once,
/// hashes the record's content (JCS), qualify-seals it when a QTSP is configured, and posts it
/// under a stable `inferenceId`. Throws on failure, for the delivery worker to classify.
@Component
public class ErnestRecordWriter {

    /// `qualified`: carries a QTSP seal (false when recorded without one).
    public record Receipt(boolean qualified, Long blockIndex) {}

    private final RestClient ernest;
    private final ErnestSealer sealer;
    private final boolean enabled;
    private final Set<String> ensuredModels = ConcurrentHashMap.newKeySet();

    public ErnestRecordWriter(IntegrationProperties props) {
        this.ernest = ErnestHttp.writeClient(props);
        String cscUrl = props.qtsp() != null ? props.qtsp().cscUrl() : null;
        this.sealer = cscUrl != null && !cscUrl.isBlank() ? new ErnestSealer(cscUrl) : null;
        this.enabled = props.ernest().baseUrl() != null && !props.ernest().baseUrl().isBlank();
    }

    /// `content` is what gets hashed (and so proved); `metadata` is stored alongside it.
    public Receipt record(String modelId, String modelName, String inferenceId, Map<String, Object> content,
                          Map<String, Object> metadata) {
        if (!enabled) throw new IllegalStateException("Ernest evidence endpoint is not configured");
        ensureModel(modelId, modelName);
        String contentHash = ErnestSealer.sha256Canonical(content);

        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("type", "inference");
        signed.put("modelId", modelId);
        signed.put("inferenceId", inferenceId);
        signed.put("inputHash", contentHash);
        signed.put("outputHash", contentHash);
        signed.put("metadata", metadata);

        Map<String, Object> body = new LinkedHashMap<>(signed);
        body.remove("type");
        if (sealer != null) body.put("signature", sealer.seal(signed));

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = ernest.post().uri("/api/inferences").body(body).retrieve().body(Map.class);
        return new Receipt(sealer != null, blockIndex(resp));
    }

    private void ensureModel(String modelId, String modelName) {
        if (ensuredModels.contains(modelId)) return;
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("type", "model_registration");
        signed.put("modelId", modelId);
        signed.put("modelName", modelName);
        signed.put("version", "1");
        signed.put("modelHash", ErnestSealer.sha256Canonical(modelId));
        signed.put("gitCommit", "0000000");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", modelId);
        body.put("modelName", modelName);
        body.put("version", "1");
        body.put("mlflow", Map.of("modelHash", ErnestSealer.sha256Canonical(modelId), "gitCommit", "0000000"));
        if (sealer != null) body.put("signature", sealer.seal(signed));
        try {
            ernest.post().uri("/api/models").body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (!ErnestHttp.isAlreadyRegistered(e)) throw e; // only a duplicate is benign
        }
        ensuredModels.add(modelId);
    }

    private static Long blockIndex(Map<String, Object> resp) {
        if (resp == null) return null;
        if (resp.get("blockIndex") instanceof Number n) return n.longValue();
        if (resp.get("blockchain") instanceof Map<?, ?> bc && bc.get("index") instanceof Number n) return n.longValue();
        return null;
    }
}
