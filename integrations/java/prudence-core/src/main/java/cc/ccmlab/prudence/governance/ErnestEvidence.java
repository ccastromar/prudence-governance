package cc.ccmlab.prudence.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Ernest evidence client: seals that a governed execution started, under the AI system's id. Only
 * SHA-256 hashes of inputs and outputs travel; raw data never leaves the process. The decision
 * itself is sealed by Prudence.
 *
 * <p>Direct ({@code POST /api/inferences}, the default) or through Ernest's event-ingestor ({@code
 * POST /events}, set ERNEST_INGESTOR_URL), queued and deduplicated by the inference id, for volume.
 * Qualified electronic seals (the Python core's {@code QtspSealer}) are not available here yet.
 */
public final class ErnestEvidence {
  /** The HTTP status (null when nothing answered) and the body. */
  public record Result(Integer status, JsonNode body) {}

  private static final long[] RETRY_MILLIS = {500, 1000, 2000, 2000, 3000, 0};

  private final String baseUrl;
  private final String apiKey;
  private final String ingestorUrl;
  private final String ingestorKey;
  private final String source;
  private final Duration timeout;
  private final HttpClient http;

  private ErnestEvidence(Builder b) {
    this.baseUrl =
        PrudenceGate.firstNonBlank(b.baseUrl, System.getenv("ERNEST_URL"), "http://127.0.0.1:3001")
            .replaceAll("/+$", "");
    this.apiKey = b.apiKey != null ? b.apiKey : orEmpty(System.getenv("ERNEST_API_KEY"));
    String ingestor =
        PrudenceGate.firstNonBlank(b.ingestorUrl, System.getenv("ERNEST_INGESTOR_URL"));
    this.ingestorUrl = ingestor == null ? "" : ingestor.replaceAll("/+$", "");
    this.ingestorKey =
        b.ingestorKey != null ? b.ingestorKey : orEmpty(System.getenv("ERNEST_INGESTOR_KEY"));
    this.source = b.source;
    this.timeout = b.timeout;
    this.http = HttpClient.newBuilder().connectTimeout(b.timeout).build();
  }

  /** Evidence configured from the environment (ERNEST_URL, ERNEST_API_KEY, …). */
  public ErnestEvidence() {
    this(new Builder());
  }

  public static Builder builder() {
    return new Builder();
  }

  private Result post(String url, ObjectNode body, String... headers) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      for (int i = 0; i + 1 < headers.length; i += 2) {
        if (!headers[i + 1].isEmpty()) request.header(headers[i], headers[i + 1]);
      }
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      JsonNode answer;
      try {
        answer = Json.MAPPER.readTree(response.body());
      } catch (Exception e) {
        String text = response.body();
        answer =
            Json.MAPPER
                .createObjectNode()
                .put("status", response.statusCode())
                .put("text", text.substring(0, Math.min(200, text.length())));
      }
      return new Result(response.statusCode(), answer);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return new Result(
          null, Json.MAPPER.createObjectNode().put("error", String.valueOf(e.getMessage())));
    }
  }

  /** One step record: only hashes of {@code inputs} and {@code output}. */
  public Result logStep(String modelId, Object inputs, Object output, ObjectNode metadata) {
    ObjectNode body = Json.MAPPER.createObjectNode();
    String inferenceId = UUID.randomUUID().toString();
    body.put("modelId", modelId)
        .put("inferenceId", inferenceId)
        .put("inputHash", Json.sha256(inputs))
        .put("outputHash", Json.sha256(output));
    if (metadata != null) body.set("metadata", metadata);
    if (!ingestorUrl.isEmpty()) {
      return post(
          ingestorUrl + "/events",
          body,
          "X-Ernest-Event-Source",
          source,
          "X-Ernest-Event-Type",
          "inference.logged",
          "X-Ernest-Source-Event-Id",
          inferenceId,
          "X-Ernest-Ingest-Key",
          ingestorKey);
    }
    return post(baseUrl + "/api/inferences", body, "X-Ernest-Api-Key", apiKey);
  }

  /**
   * Seal that a governed execution started ({@code stage: execution} under the AI system's id),
   * which Prudence compares with the runtime's own report. Pass the same {@code executionId} given
   * to {@link PrudenceGate#reportExecution}: it travels as the evidence's {@code correlationId}.
   * Best effort: never throws.
   *
   * <p>Prudence registers the system in Ernest with its first decision, which can land a moment
   * after the run started; Ernest then answers 404 or 409 and the seal is retried for a few
   * seconds, so call this off the tool's path.
   */
  public Result logExecution(
      String aiSystemId,
      String pipeline,
      String runtime,
      String decisionId,
      Object inputs,
      String executionId) {
    ObjectNode metadata = Json.MAPPER.createObjectNode();
    metadata
        .put("stage", "execution")
        .put("aiSystemId", aiSystemId)
        .put("pipeline", pipeline)
        .put("runtime", runtime)
        .put("decisionId", decisionId);
    if (executionId != null) metadata.put("correlationId", executionId);
    Object hashed = inputs == null ? Json.MAPPER.createObjectNode() : inputs;
    ObjectNode output = Json.MAPPER.createObjectNode().put("decisionId", decisionId);
    Result result = new Result(null, null);
    for (long delay : RETRY_MILLIS) {
      result = logStep(aiSystemId, hashed, output, metadata);
      Integer status = result.status();
      if ((status != null && status != 404 && status != 409) || delay == 0) return result;
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return result;
      }
    }
    return result;
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  public static final class Builder {
    private String baseUrl;
    private String apiKey;
    private String ingestorUrl;
    private String ingestorKey;
    private String source = "prudence-agent";
    private Duration timeout = Duration.ofSeconds(5);

    /** Ernest's base URL; else ERNEST_URL, else http://127.0.0.1:3001. */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /** Ernest's event-ingestor; else ERNEST_INGESTOR_URL (unset: direct). */
    public Builder ingestorUrl(String ingestorUrl) {
      this.ingestorUrl = ingestorUrl;
      return this;
    }

    public Builder ingestorKey(String ingestorKey) {
      this.ingestorKey = ingestorKey;
      return this;
    }

    public Builder source(String source) {
      this.source = source;
      return this;
    }

    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    public ErnestEvidence build() {
      return new ErnestEvidence(this);
    }
  }
}
