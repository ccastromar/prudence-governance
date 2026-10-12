package com.prudence.evidence.adapter.out.ernest;

import com.prudence.config.IntegrationProperties;
import com.prudence.evidence.application.port.out.EvidencePort;
import com.prudence.policy.domain.DecisionContext;
import com.prudence.policy.domain.PolicyDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/// Driven adapter to Ernest. Records each governance decision as a tamper-evident provenance
/// block — and, when a QTSP is configured (ADR-002 B2), qualify-seals it, so the control
/// plane proves its own decisions exactly as the runtime agents prove their steps. The AI
/// system's registry id is used as the Ernest model id, tying the governance record to the
/// system it governs (and letting posture read evidence back per system).
///
/// Best-effort: evidence recording never blocks or fails a decision. Without an Ernest key
/// or QTSP endpoint it falls back to logging (dev/tests stay offline).
@Component
public class ErnestEvidenceAdapter implements EvidencePort {

    private static final Logger log = LoggerFactory.getLogger(ErnestEvidenceAdapter.class);

    private final RestClient ernest;
    private final ErnestSealer sealer;
    private final boolean enabled;
    private final Set<String> registeredModels = ConcurrentHashMap.newKeySet();

    public ErnestEvidenceAdapter(IntegrationProperties props) {
        String apiKey = props.ernest().apiKey();
        RestClient.Builder b = RestClient.builder().baseUrl(props.ernest().baseUrl());
        if (apiKey != null && !apiKey.isBlank()) {
            b.defaultHeader("X-Ernest-Api-Key", apiKey);
        }
        this.ernest = b.build();

        String cscUrl = props.qtsp() != null ? props.qtsp().cscUrl() : null;
        boolean canSeal = apiKey != null && !apiKey.isBlank() && cscUrl != null && !cscUrl.isBlank();
        this.sealer = canSeal ? new ErnestSealer(cscUrl) : null;
        this.enabled = canSeal;
    }

    @Override
    public void recordDecision(DecisionContext context, PolicyDecision decision) {
        String verdict = switch (decision) {
            case PolicyDecision.Allow ignored -> "ALLOW";
            case PolicyDecision.ApprovalRequired ignored -> "APPROVAL_REQUIRED";
            case PolicyDecision.Deny ignored -> "DENY";
        };
        String reason = switch (decision) {
            case PolicyDecision.ApprovalRequired ar -> ar.reason();
            case PolicyDecision.Deny d -> d.reason();
            case PolicyDecision.Allow ignored -> null;
        };

        if (!enabled) {
            // No Ernest key / QTSP configured: log the intent (dev / offline / tests).
            log.info("governance decision (unsealed): subject={} pipeline={} system={} -> {}",
                    context.subject(), context.pipeline(), context.aiSystemId(), verdict);
            return;
        }

        try {
            ensureModel(context.aiSystemId());
            sealAndSubmitDecision(context, verdict, reason);
            log.info("governance decision sealed in Ernest: system={} -> {}", context.aiSystemId(), verdict);
        } catch (Exception e) {
            // Never block a decision on evidence recording.
            log.warn("could not seal decision evidence in Ernest (system={}): {}",
                    context.aiSystemId(), e.getMessage());
        }
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
        body.put("signature", sealer.seal(signed));

        try {
            ernest.post().uri("/api/models").body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            // A model for this system may already exist from a prior run — fine, the
            // registration block is on-chain. Any other 4xx we let the caller log.
            if (e.getStatusCode().value() != 400) throw e;
            log.debug("model {} already registered (or rejected): {}", aiSystemId, e.getResponseBodyAsString());
        }
        registeredModels.add(aiSystemId);
    }

    private void sealAndSubmitDecision(DecisionContext ctx, String verdict, String reason) {
        String inferenceId = UUID.randomUUID().toString();
        String inputHash = sha256Hex(ctx.subject() + "|" + ctx.pipeline() + "|" + ctx.action());
        String outputHash = sha256Hex(verdict + "|" + (reason == null ? "" : reason));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stage", "governance_decision");
        metadata.put("subject", ctx.subject());
        metadata.put("pipeline", ctx.pipeline());
        metadata.put("action", ctx.action());
        metadata.put("aiSystemId", ctx.aiSystemId());
        metadata.put("decision", verdict);
        if (reason != null) metadata.put("reason", reason);

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
        body.put("signature", sealer.seal(signed));

        ernest.post().uri("/api/inferences").body(body).retrieve().toBodilessEntity();
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
