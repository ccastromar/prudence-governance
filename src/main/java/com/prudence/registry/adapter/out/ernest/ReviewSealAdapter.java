package com.prudence.registry.adapter.out.ernest;

import com.prudence.config.IntegrationProperties;
import com.prudence.evidence.adapter.out.ernest.ErnestHttp;
import com.prudence.evidence.adapter.out.ernest.ErnestSealer;
import com.prudence.registry.application.port.out.ReviewSealPort;
import com.prudence.registry.domain.ReviewEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/// ACL adapter: records a governance review event in Ernest (model "prudence-reviews") and
/// additionally qualify-seals it (ADR-002 B2) when a QTSP is configured. Failures propagate to
/// the evidence delivery worker, which retries them under ErnestHttp's contract.
@Component
public class ReviewSealAdapter implements ReviewSealPort {

  private static final String MODEL_ID = "prudence-reviews";

  private final RestClient ernest;
  private final ErnestSealer sealer;
  private final boolean enabled;
  private volatile boolean modelEnsured = false;

  public ReviewSealAdapter(IntegrationProperties props) {
    this.ernest = ErnestHttp.writeClient(props);

    String cscUrl = props.qtsp() != null ? props.qtsp().cscUrl() : null;
    boolean canSeal = cscUrl != null && !cscUrl.isBlank();
    this.sealer = canSeal ? new ErnestSealer(cscUrl) : null;
    this.enabled = props.ernest().baseUrl() != null && !props.ernest().baseUrl().isBlank();
  }

  @Override
  public SealResult seal(ReviewEvent e) {
    if (!enabled) throw new IllegalStateException("Ernest evidence endpoint is not configured");
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
    if (sealer != null) body.put("signature", sealer.seal(signed));

    @SuppressWarnings("unchecked")
    Map<String, Object> resp =
        ernest.post().uri("/api/inferences").body(body).retrieve().body(Map.class);
    return new SealResult(sealer != null, extractBlockIndex(resp));
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
    body.put(
        "mlflow",
        Map.of("modelHash", ErnestSealer.sha256Canonical(MODEL_ID), "gitCommit", "0000000"));
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
    if (resp.get("blockIndex") instanceof Number n) return n.longValue();
    if (resp.get("blockchain") instanceof Map<?, ?> bc && bc.get("index") instanceof Number n)
      return n.longValue();
    return null;
  }
}
