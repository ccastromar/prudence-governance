package com.prudence.attestation.adapter.out.ernest;

import com.prudence.attestation.application.port.out.AttestationEvidenceQueryPort;
import com.prudence.attestation.application.port.out.AttestationSealPort;
import com.prudence.config.IntegrationProperties;
import com.prudence.evidence.adapter.out.ernest.ErnestHttp;
import com.prudence.evidence.adapter.out.ernest.ErnestSealer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// ACL adapter: records an attestation's content hash in Ernest under a well-known model
/// ("prudence-attestations") and additionally qualify-seals it (ADR-002 B2) when a QTSP is
/// configured. Failures propagate to the evidence delivery worker, which retries them.
@Component
public class ErnestAttestationAdapter implements AttestationSealPort, AttestationEvidenceQueryPort {

    private static final Logger log = LoggerFactory.getLogger(ErnestAttestationAdapter.class);
    private static final String MODEL_ID = "prudence-attestations";

    private final RestClient ernest;
    private final ErnestSealer sealer;
    private final boolean enabled;
    private volatile boolean modelEnsured = false;

    public ErnestAttestationAdapter(IntegrationProperties props) {
        this.ernest = ErnestHttp.client(props);

        String cscUrl = props.qtsp() != null ? props.qtsp().cscUrl() : null;
        boolean canSeal = cscUrl != null && !cscUrl.isBlank();
        this.sealer = canSeal ? new ErnestSealer(cscUrl) : null;
        this.enabled = props.ernest().baseUrl() != null && !props.ernest().baseUrl().isBlank();
    }

    @Override
    public Receipt seal(String attestationId, String contentHash, Map<String, Object> summary) {
        if (!enabled) throw new IllegalStateException("Ernest evidence endpoint is not configured");
        ensureModel();

        Map<String, Object> metadata = new LinkedHashMap<>(summary);
        metadata.put("stage", "attestation");
        metadata.put("attestationId", attestationId);

        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("type", "inference");
        signed.put("modelId", MODEL_ID);
        signed.put("inferenceId", attestationId);
        signed.put("inputHash", contentHash);
        signed.put("outputHash", contentHash);
        signed.put("metadata", metadata);

        Map<String, Object> body = new LinkedHashMap<>(signed);
        body.remove("type");
        if (sealer != null) body.put("signature", sealer.seal(signed));

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = ernest.post().uri("/api/inferences").body(body).retrieve().body(Map.class);
        Long blockIndex = extractBlockIndex(resp);
        boolean qualified = sealer != null;
        return new Receipt(qualified, blockIndex,
                (qualified ? "Qualified-sealed" : "Recorded without qualified seal") + " in Ernest"
                        + (blockIndex != null ? " (block " + blockIndex + ")" : "") + ".");
    }

    @Override
    public Confirmation confirm(String modelId, String contentHash) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ernest.get()
                    .uri("/api/provenances/{m}", modelId).retrieve().body(Map.class);
            if (body == null) return new Confirmation(true, false, false, null);
            boolean chainValid = Boolean.TRUE.equals(body.get("chainValid"));
            Long blockIndex = null;
            boolean present = false;
            if (body.get("history") instanceof List<?> history) {
                for (Object item : history) {
                    if (item instanceof Map<?, ?> entry && contentHash.equals(entry.get("inputHash"))) {
                        present = true;
                        if (entry.get("blockIndex") instanceof Number n) blockIndex = n.longValue();
                        break;
                    }
                }
            }
            return new Confirmation(true, present, chainValid, blockIndex);
        } catch (Exception e) {
            log.warn("could not confirm attestation seal in Ernest: {}", e.getMessage());
            return new Confirmation(false, false, false, null);
        }
    }

    private void ensureModel() {
        if (modelEnsured) return;
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("type", "model_registration");
        signed.put("modelId", MODEL_ID);
        signed.put("modelName", "Prudence attestations");
        signed.put("version", "1");
        signed.put("modelHash", ErnestSealer.sha256Canonical(MODEL_ID));
        signed.put("gitCommit", "0000000");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", MODEL_ID);
        body.put("modelName", "Prudence attestations");
        body.put("version", "1");
        body.put("mlflow", Map.of("modelHash", ErnestSealer.sha256Canonical(MODEL_ID), "gitCommit", "0000000"));
        if (sealer != null) body.put("signature", sealer.seal(signed));
        try {
            ernest.post().uri("/api/models").body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (!ErnestHttp.isAlreadyRegistered(e)) throw e; // only a duplicate is benign
        }
        modelEnsured = true;
    }

    private static Long extractBlockIndex(Map<String, Object> resp) {
        if (resp == null) return null;
        Object idx = resp.get("blockIndex");
        if (idx instanceof Number n) return n.longValue();
        if (resp.get("blockchain") instanceof Map<?, ?> bc && bc.get("index") instanceof Number n) return n.longValue();
        return null;
    }
}
