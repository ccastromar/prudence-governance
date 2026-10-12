package com.prudence.attestation.adapter.out.ernest;

import com.prudence.attestation.application.port.out.AttestationEvidenceQueryPort;
import com.prudence.attestation.application.port.out.AttestationSealPort;
import com.prudence.attestation.domain.Attestation;
import com.prudence.config.IntegrationProperties;
import com.prudence.evidence.adapter.out.ernest.ErnestSealer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// ACL adapter: seals an attestation's content hash into Ernest as a provenance block under
/// a well-known model ("prudence-attestations"), qualify-sealed (ADR-002 B2) when a QTSP is
/// configured. Fail-soft — if Ernest/QTSP is unavailable the report is returned unsealed.
@Component
public class ErnestAttestationAdapter implements AttestationSealPort, AttestationEvidenceQueryPort {

    private static final Logger log = LoggerFactory.getLogger(ErnestAttestationAdapter.class);
    private static final String MODEL_ID = "prudence-attestations";

    private final RestClient ernest;
    private final ErnestSealer sealer;
    private final boolean enabled;
    private volatile boolean modelEnsured = false;

    public ErnestAttestationAdapter(IntegrationProperties props) {
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
    public Attestation.Evidence seal(String attestationId, String contentHash, Map<String, Object> summary) {
        if (!enabled) {
            return new Attestation.Evidence(false, null, MODEL_ID,
                    "Evidence plane not configured — report issued unsealed.");
        }
        try {
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
            body.put("signature", sealer.seal(signed));

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = ernest.post().uri("/api/inferences").body(body).retrieve().body(Map.class);
            Long blockIndex = extractBlockIndex(resp);
            return new Attestation.Evidence(true, blockIndex, MODEL_ID,
                    "Sealed in Ernest" + (blockIndex != null ? " (block " + blockIndex + ")" : "") + ".");
        } catch (Exception e) {
            log.warn("could not seal attestation {} in Ernest: {}", attestationId, e.getMessage());
            return new Attestation.Evidence(false, null, MODEL_ID, "Ernest seal failed: " + e.getMessage());
        }
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
        body.put("signature", sealer.seal(signed));
        try {
            ernest.post().uri("/api/models").body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 400) throw e; // 400 == already registered
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
