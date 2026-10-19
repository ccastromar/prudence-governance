package com.prudence.evidence.sink;

import com.prudence.config.IntegrationProperties;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/// Which evidence store this installation uses (`prudence.evidence.sink`) and what it can
/// guarantee (ADR-001). Ernest gives custody, proofs and read-back always, and time when its
/// anchoring is on (asked, never assumed). Only the Ernest sink exists so far: any other value
/// stops start-up rather than run with a store Prudence cannot deliver to.
@Service
public class EvidenceSinkService {

  public static final String ERNEST = "ernest";

  private final String kind;
  private final String ernestUrl;
  private final RestClient ernest;

  public EvidenceSinkService(
      @Value("${prudence.evidence.sink:ernest}") String kind, IntegrationProperties props) {
    this.kind = kind == null || kind.isBlank() ? ERNEST : kind.trim().toLowerCase();
    if (!ERNEST.equals(this.kind)) {
      throw new IllegalStateException(
          "prudence.evidence.sink=" + kind + " is not supported yet (only 'ernest')");
    }
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
    Set<EvidenceCapability> caps =
        EnumSet.of(EvidenceCapability.CUSTODY, EvidenceCapability.PROOF, EvidenceCapability.READ_BACK);
    if (ernestAnchors()) caps.add(EvidenceCapability.TIME);
    return caps;
  }

  public EvidenceSinkInfo info() {
    Set<EvidenceCapability> caps = capabilities();
    List<EvidenceSinkInfo.Level> have =
        caps.stream().map(EvidenceSinkInfo.Level::of).toList();
    List<EvidenceSinkInfo.Level> missing =
        Arrays.stream(EvidenceCapability.values())
            .filter(c -> !caps.contains(c))
            .map(EvidenceSinkInfo.Level::of)
            .toList();
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
