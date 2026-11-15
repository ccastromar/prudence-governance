package cc.ccmlab.prudence.governance;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** OAuth2 client credentials for a secured PDP. {@code tokenUrl} is the IdP's token endpoint. */
public final class M2MAuth {
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final String scope;
  private final HttpClient http = HttpClient.newHttpClient();
  private String token = "";
  private long expiresAtMillis;

  public M2MAuth(String tokenUrl, String clientId, String clientSecret, String scope) {
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.scope = scope == null || scope.isEmpty() ? "pdp.decide" : scope;
  }

  /**
   * From PRUDENCE_PDP_TOKEN_URL, PRUDENCE_PDP_CLIENT_ID, PRUDENCE_PDP_CLIENT_SECRET (and
   * PRUDENCE_PDP_SCOPE); null when any of the first three is missing.
   */
  public static M2MAuth fromEnv() {
    String url = System.getenv("PRUDENCE_PDP_TOKEN_URL");
    String id = System.getenv("PRUDENCE_PDP_CLIENT_ID");
    String secret = System.getenv("PRUDENCE_PDP_CLIENT_SECRET");
    if (isBlank(url) || isBlank(id) || isBlank(secret)) return null;
    return new M2MAuth(url, id, secret, System.getenv("PRUDENCE_PDP_SCOPE"));
  }

  public synchronized String bearer(Duration timeout) throws Exception {
    if (!token.isEmpty() && System.currentTimeMillis() < expiresAtMillis - 30_000) return token;
    Map<String, String> form =
        Map.of(
            "grant_type",
            "client_credentials",
            "client_id",
            clientId,
            "client_secret",
            clientSecret,
            "scope",
            scope);
    String body =
        form.entrySet().stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(tokenUrl))
            .timeout(timeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("token endpoint answered HTTP " + response.statusCode());
    }
    JsonNode answer = Json.MAPPER.readTree(response.body());
    String accessToken = answer.path("access_token").asText("");
    if (accessToken.isEmpty())
      throw new IllegalStateException("token endpoint gave no access_token");
    token = accessToken;
    expiresAtMillis = System.currentTimeMillis() + answer.path("expires_in").asLong(300) * 1000;
    return token;
  }

  static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
