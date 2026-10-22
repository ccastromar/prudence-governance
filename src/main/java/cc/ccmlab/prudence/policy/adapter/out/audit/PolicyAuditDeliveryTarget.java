package cc.ccmlab.prudence.policy.adapter.out.audit;

import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.evidence.adapter.out.ernest.ErnestHttp;
import cc.ccmlab.prudence.evidence.adapter.out.ernest.ErnestRecordWriter;
import cc.ccmlab.prudence.evidence.adapter.out.ernest.ErnestSealer;
import cc.ccmlab.prudence.evidence.delivery.Delivery;
import cc.ccmlab.prudence.evidence.delivery.DeliveryTarget;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Policy audit events have their own Ernest model and stable IDs, separate from AI-system reviews.
 */
@Component
@ConditionalOnProperty(
    prefix = "prudence.evidence",
    name = "sink",
    havingValue = "ernest",
    matchIfMissing = true)
public class PolicyAuditDeliveryTarget implements DeliveryTarget<PolicyAuditEntity> {
  private static final String MODEL = "prudence-policy-audit";
  private final PolicyAuditRepository repository;
  private final RestClient client;
  private final ErnestSealer sealer;
  private volatile boolean ensured;

  public PolicyAuditDeliveryTarget(PolicyAuditRepository repository, IntegrationProperties props) {
    this.repository = repository;
    this.client = ErnestHttp.writeClient(props);
    String url = props.qtsp() == null ? null : props.qtsp().cscUrl();
    this.sealer = url == null || url.isBlank() ? null : new ErnestSealer(url);
  }

  public String name() {
    return "policy-audit";
  }

  public List<PolicyAuditEntity> due(String now, int limit) {
    return repository.due(now, Limit.of(limit));
  }

  public String id(PolicyAuditEntity e) {
    return e.getId();
  }

  public Delivery delivery(PolicyAuditEntity e) {
    return e.getDelivery();
  }

  public int claim(String id, String expected, String until) {
    return repository.claim(id, expected, until);
  }

  public Optional<PolicyAuditEntity> find(String id) {
    return repository.findById(id);
  }

  public List<PolicyAuditEntity> deadLettered(int limit) {
    return repository.deadLettered(Limit.of(limit));
  }

  public void save(PolicyAuditEntity e) {
    repository.save(e);
  }

  public Consumer<PolicyAuditEntity> send(PolicyAuditEntity event) {
    ensureModel();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("stage", "policy_administration");
    metadata.put("aggregateId", event.getAggregateId());
    metadata.put("action", event.getEventType());
    metadata.put("actor", event.getActor());
    metadata.put("occurredAt", event.getOccurredAt());
    metadata.put("details", event.getDetailsJson());
    String hash = ErnestSealer.sha256Canonical(metadata);
    Map<String, Object> signed = new LinkedHashMap<>();
    signed.put("type", "inference");
    signed.put("modelId", MODEL);
    signed.put("inferenceId", event.getId());
    signed.put("inputHash", hash);
    signed.put("outputHash", hash);
    signed.put("metadata", metadata);
    Map<String, Object> body = new LinkedHashMap<>(signed);
    body.remove("type");
    if (sealer != null) body.put("signature", sealer.seal(signed));
    client.post().uri("/api/inferences").body(body).retrieve().toBodilessEntity();
    return ignored -> {};
  }

  private synchronized void ensureModel() {
    if (ensured) return;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("modelId", MODEL);
    body.put("modelName", "Prudence policy administration");
    body.put("version", "1");
    body.put(
        "mlflow", Map.of("modelHash", ErnestSealer.sha256Canonical(MODEL), "gitCommit", "0000000"));
    body.put("metadata", ErnestRecordWriter.REGISTER_METADATA);
    if (sealer != null) {
      Map<String, Object> signed = new LinkedHashMap<>();
      signed.put("type", "model_registration");
      signed.put("modelId", MODEL);
      signed.put("modelName", "Prudence policy administration");
      signed.put("version", "1");
      signed.put("modelHash", ErnestSealer.sha256Canonical(MODEL));
      signed.put("gitCommit", "0000000");
      signed.put("metadata", ErnestRecordWriter.REGISTER_METADATA);
      body.put("signature", sealer.seal(signed));
    }
    try {
      client.post().uri("/api/models").body(body).retrieve().toBodilessEntity();
    } catch (RestClientResponseException e) {
      if (!ErnestHttp.isAlreadyRegistered(e)) throw e;
    }
    ensured = true;
  }
}
