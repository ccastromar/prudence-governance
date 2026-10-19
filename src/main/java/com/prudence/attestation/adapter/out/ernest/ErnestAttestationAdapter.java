package com.prudence.attestation.adapter.out.ernest;

import com.prudence.attestation.application.port.out.AttestationEvidenceQueryPort;
import com.prudence.attestation.application.port.out.AttestationSealPort;
import com.prudence.config.IntegrationProperties;
import com.prudence.evidence.adapter.out.ernest.ErnestEventProof;
import com.prudence.evidence.adapter.out.ernest.ErnestHttp;
import com.prudence.evidence.adapter.out.ernest.ErnestRecordWriter;
import com.prudence.evidence.adapter.out.ernest.ErnestSealer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/// ACL adapter: records an attestation's content hash in Ernest under a well-known model
/// ("prudence-attestations") and additionally qualify-seals it (ADR-002 B2) when a QTSP is
/// configured. Failures propagate to the evidence delivery worker, which retries them.
@Component
@ConditionalOnProperty(
    prefix = "prudence.evidence",
    name = "sink",
    havingValue = "ernest",
    matchIfMissing = true)
public class ErnestAttestationAdapter implements AttestationSealPort, AttestationEvidenceQueryPort {

  private static final Logger log = LoggerFactory.getLogger(ErnestAttestationAdapter.class);
  private static final String MODEL_ID = "prudence-attestations";

  private final RestClient ernest;
  /// Proofs and verification are reads: they go to any Ernest replica.
  private final RestClient reader;
  private final ErnestSealer sealer;
  private final boolean enabled;
  private volatile boolean modelEnsured = false;

  public ErnestAttestationAdapter(IntegrationProperties props) {
    this.ernest = ErnestHttp.writeClient(props);
    this.reader = ErnestHttp.readClient(props);

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
    Map<String, Object> resp =
        ernest.post().uri("/api/inferences").body(body).retrieve().body(Map.class);
    Long blockIndex = extractBlockIndex(resp);
    boolean qualified = sealer != null;
    // With Ernest ADR-004 batching the record is accepted before it is chained (202,
    // no block yet): accepted is delivered, and the block is fetched lazily on verify.
    return new Receipt(
        qualified,
        blockIndex,
        (qualified ? "Qualified-sealed" : "Recorded without qualified seal")
            + " in Ernest"
            + (blockIndex != null ? " (block " + blockIndex + ")" : " (chaining pending)")
            + ".");
  }

  /// Prefers the event's own inclusion proof (Ernest ADR-004), verified here — block hash and
  /// RFC 6962 path — rather than trusted; falls back to scanning the model's provenance when
  /// Ernest has no event store (EVIDENCE_BATCHING=off answers 404).
  @Override
  public Confirmation confirm(String modelId, String eventId, String contentHash) {
    try {
      @SuppressWarnings("unchecked")
      ResponseEntity<Map> resp =
          reader
              .get()
              .uri("/api/events/{source}/{eventId}/proof", "api/" + modelId, eventId)
              .retrieve()
              .toEntity(Map.class);
      Map<String, Object> proof = resp.getBody();
      if (resp.getStatusCode().value() == 202 || proof == null) {
        return new Confirmation(true, false, false, null, true); // recorded, not chained yet
      }
      ErnestEventProof.Result checked = ErnestEventProof.verify(proof);
      if (!checked.valid()) {
        log.warn("Ernest proof for attestation {} does not verify: {}", eventId, checked.reason());
        return new Confirmation(true, true, false, null);
      }
      boolean present = contentHash.equals(checked.eventData().get("inputHash"));
      return new Confirmation(true, present, present && chainVerifies(), checked.blockIndex());
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) return confirmByProvenance(modelId, contentHash);
      // 409: Ernest refuses to emit a proof its store cannot reproduce — tamper evidence.
      if (e.getStatusCode().value() == 409) return new Confirmation(true, true, false, null);
      log.warn("could not confirm attestation seal in Ernest: {}", e.getMessage());
      return new Confirmation(false, false, false, null);
    } catch (Exception e) {
      log.warn("could not confirm attestation seal in Ernest: {}", e.getMessage());
      return new Confirmation(false, false, false, null);
    }
  }

  /// Incremental chain verification (GET /api/verify); fail-closed.
  private boolean chainVerifies() {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = reader.get().uri("/api/verify").retrieve().body(Map.class);
      return body != null && Boolean.TRUE.equals(body.get("isValid"));
    } catch (Exception e) {
      return false;
    }
  }

  private Confirmation confirmByProvenance(String modelId, String contentHash) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body =
          reader.get().uri("/api/provenances/{m}", modelId).retrieve().body(Map.class);
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
    signed.put("metadata", ErnestRecordWriter.REGISTER_METADATA);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("modelId", MODEL_ID);
    body.put("modelName", "Prudence attestations");
    body.put("version", "1");
    body.put(
        "mlflow",
        Map.of("modelHash", ErnestSealer.sha256Canonical(MODEL_ID), "gitCommit", "0000000"));
    body.put("metadata", ErnestRecordWriter.REGISTER_METADATA);
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
    if (resp.get("blockchain") instanceof Map<?, ?> bc && bc.get("index") instanceof Number n)
      return n.longValue();
    return null;
  }
}
