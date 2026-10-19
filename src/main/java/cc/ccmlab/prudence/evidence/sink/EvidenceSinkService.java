package cc.ccmlab.prudence.evidence.sink;

import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.evidence.adapter.out.cloudevents.CloudEventsTransport;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/// Which evidence store this installation uses (`prudence.evidence.sink`) and what it can
/// guarantee (ADR-001). Ernest gives custody, proofs and read-back always, and time when its
/// anchoring is on (asked, never assumed). `cloudevents` (CloudEvents on Kafka, for a company's
/// own audit store) gives custody only. Any other value stops start-up.
@Service
public class EvidenceSinkService {

  public static final String ERNEST = "ernest";
  public static final String CLOUDEVENTS = "cloudevents";

  private final String kind;
  private final String ernestUrl;
  private final RestClient ernest;
  private final ObjectProvider<CloudEventsTransport> cloudEvents;

  public EvidenceSinkService(
      @Value("${prudence.evidence.sink:ernest}") String kind,
      IntegrationProperties props,
      ObjectProvider<CloudEventsTransport> cloudEvents) {
    this.kind = kind == null || kind.isBlank() ? ERNEST : kind.trim().toLowerCase();
    if (!ERNEST.equals(this.kind) && !CLOUDEVENTS.equals(this.kind)) {
      throw new IllegalStateException(
          "prudence.evidence.sink must be 'ernest' or 'cloudevents' (was " + kind + ")");
    }
    this.cloudEvents = cloudEvents;
    this.ernestUrl = props.ernest().baseUrl();
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2_000);
    factory.setReadTimeout(3_000);
    if (ernestUrl == null || ernestUrl.isBlank()) {
      this.ernest = null;
    } else {
      var builder = RestClient.builder().baseUrl(ernestUrl).requestFactory(factory);
      String apiKey = props.ernest().apiKey();
      if (apiKey != null && !apiKey.isBlank()) builder.defaultHeader("X-Ernest-Api-Key", apiKey);
      this.ernest = builder.build();
    }
  }

  public String kind() {
    return kind;
  }

  public Set<EvidenceCapability> capabilities() {
    if (CLOUDEVENTS.equals(kind)) return EnumSet.of(EvidenceCapability.CUSTODY);
    Set<EvidenceCapability> caps =
        EnumSet.of(
            EvidenceCapability.CUSTODY, EvidenceCapability.PROOF, EvidenceCapability.READ_BACK);
    if (ernestAnchors()) caps.add(EvidenceCapability.TIME);
    return caps;
  }

  public EvidenceSinkInfo info() {
    Set<EvidenceCapability> caps = capabilities();
    List<EvidenceSinkInfo.Level> have = caps.stream().map(EvidenceSinkInfo.Level::of).toList();
    List<EvidenceSinkInfo.Level> missing =
        Arrays.stream(EvidenceCapability.values())
            .filter(c -> !caps.contains(c))
            .map(EvidenceSinkInfo.Level::of)
            .toList();
    if (CLOUDEVENTS.equals(kind)) {
      CloudEventsTransport publisher = cloudEvents.getIfAvailable();
      return new EvidenceSinkInfo(
          kind,
          "External store (CloudEvents)",
          publisher == null ? null : publisher.target(),
          have,
          missing);
    }
    return new EvidenceSinkInfo(kind, "Ernest", ernestUrl, have, missing);
  }

  /// Whether Ernest anchors its chain externally (OpenTimestamps or a contract). Unknown —
  /// unreachable, older Ernest — is not claimed.
  private boolean ernestAnchors() {
    if (ernest == null) return false;
    try {
      Map<?, ?> status = ernest.get().uri("/api/anchors/status").retrieve().body(Map.class);
      return status != null && Boolean.TRUE.equals(status.get("configured"));
    } catch (RuntimeException unreachable) {
      return false;
    }
  }
}
