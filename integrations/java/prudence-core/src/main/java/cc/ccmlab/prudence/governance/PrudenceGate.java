package cc.ccmlab.prudence.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The Policy Enforcement Point client: asks the Prudence PDP ({@code POST /api/policy/decisions})
 * and returns a typed decision. The enforcement point belongs in the runtime; the policy stays
 * outside, in Prudence.
 *
 * <p>Fail closed: any transport error, timeout, unexpected status or malformed body is a DENY. A
 * governance engine that is down must never grant access.
 */
public final class PrudenceGate {
  private static final Map<Integer, String> EXPECTED =
      Map.of(200, "ALLOW", 202, "APPROVAL_REQUIRED", 403, "DENY", 503, "ERROR");

  private final String baseUrl;
  private final Duration timeout;
  private final M2MAuth auth;
  private final String correlationId;
  private final HttpClient http;

  private PrudenceGate(Builder b) {
    String url =
        firstNonBlank(
            b.baseUrl,
            System.getenv("PRUDENCE_PDP_URL"),
            System.getenv("PRUDENCE_URL"),
            "http://127.0.0.1:8099");
    this.baseUrl = url.replaceAll("/+$", "");
    this.timeout = b.timeout;
    this.auth = b.noAuth ? null : (b.auth != null ? b.auth : M2MAuth.fromEnv());
    this.correlationId = b.correlationId == null ? null : Flow.check(b.correlationId);
    this.http = HttpClient.newBuilder().connectTimeout(b.timeout).build();
  }

  /** A gate configured from the environment (PRUDENCE_PDP_URL, PRUDENCE_PDP_TOKEN_URL, …). */
  public PrudenceGate() {
    this(new Builder());
  }

  public static Builder builder() {
    return new Builder();
  }

  public String baseUrl() {
    return baseUrl;
  }

  private HttpRequest.Builder post(String path, String json) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
    if (auth != null)
      request.header("Authorization", "Bearer " + auth.bearer(Duration.ofSeconds(5)));
    return request;
  }

  /**
   * Ask Prudence whether {@code pipeline} may run now. Never throws: anything but a valid ALLOW is
   * a refusal. The decision carries the flow's correlation id: the enclosing {@link Flow#run}, else
   * the gate's own; with none, it is sent without one.
   */
  public Decision decide(String subject, String pipeline, String aiSystemId, String action) {
    ObjectNode payload = Json.MAPPER.createObjectNode();
    payload
        .put("subject", subject)
        .put("pipeline", pipeline)
        .put("aiSystemId", aiSystemId)
        .put("action", action);
    String correlation = Flow.current() != null ? Flow.current() : correlationId;
    if (correlation != null) payload.put("correlationId", correlation);
    int status;
    JsonNode body;
    try {
      HttpResponse<String> response =
          http.send(
              post("/api/policy/decisions", payload.toString()).build(),
              HttpResponse.BodyHandlers.ofString());
      status = response.statusCode();
      body = Json.MAPPER.readTree(response.body());
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return Decision.deny(
          "PDP unreachable: "
              + e.getClass().getSimpleName()
              + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }
    if (body == null || !body.isObject())
      return Decision.deny("Invalid PDP response (HTTP " + status + ")");
    String expected = EXPECTED.get(status);
    JsonNode decisionId = body.get("decisionId");
    if (expected == null
        || !expected.equals(body.path("decision").asText(null))
        || decisionId == null
        || !decisionId.isTextual()
        || decisionId.asText().isBlank()) {
      return Decision.deny("Unexpected PDP response (HTTP " + status + ")");
    }
    return new Decision(
        expected, text(body.get("reason")), text(body.get("approver")), decisionId.asText());
  }

  /**
   * Tell Prudence this execution started under {@code decision} (Decision API v1, {@code POST
   * /api/executions}), so drift sees it even without Ernest. Best effort: never throws; returns
   * whether Prudence recorded it.
   */
  public boolean reportExecution(
      Decision decision, String aiSystemId, String pipeline, String runtime, String executionId) {
    ObjectNode report = Json.MAPPER.createObjectNode();
    report
        .put("runtime", runtime == null ? "java" : runtime)
        .put(
            "executionId",
            executionId == null || executionId.isEmpty()
                ? UUID.randomUUID().toString()
                : executionId)
        .put("decisionId", decision.decisionId())
        .put("aiSystemId", aiSystemId)
        .put("pipeline", pipeline)
        .put("startedAt", Instant.now().toString());
    try {
      int status =
          http.send(
                  post("/api/executions", report.toString()).build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode();
      return status == 200 || status == 201;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return false;
    }
  }

  private static String text(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  static String firstNonBlank(String... values) {
    for (String v : values) if (v != null && !v.isBlank()) return v;
    return null;
  }

  public static final class Builder {
    private String baseUrl;
    private Duration timeout = Duration.ofSeconds(3);
    private M2MAuth auth;
    private boolean noAuth;
    private String correlationId;

    /**
     * Prudence's base URL; else PRUDENCE_PDP_URL, else PRUDENCE_URL, else http://127.0.0.1:8099.
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /** Per request (default 3 s). */
    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    /** Credentials for a secured Prudence; else {@link M2MAuth#fromEnv()}. */
    public Builder auth(M2MAuth auth) {
      this.auth = auth;
      return this;
    }

    /** No credentials, whatever the environment says (open development profile). */
    public Builder noAuth() {
      this.noAuth = true;
      return this;
    }

    /** A fixed flow id for every decision of this gate; usually use {@link Flow#run} instead. */
    public Builder correlationId(String correlationId) {
      this.correlationId = correlationId;
      return this;
    }

    public PrudenceGate build() {
      return new PrudenceGate(this);
    }
  }
}
