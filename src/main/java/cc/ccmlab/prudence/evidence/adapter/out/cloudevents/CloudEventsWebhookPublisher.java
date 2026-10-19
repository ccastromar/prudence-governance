package cc.ccmlab.prudence.evidence.adapter.out.cloudevents;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/// Prudence's evidence as CloudEvents 1.0 (structured mode), one HTTPS POST per event to a
/// webhook of the company's audit store (ADR-001, custody-only sink): a SIEM collector, a
/// log intake, its own ledger. A 2xx answer is the acknowledgement; anything else is a
/// failure the delivery worker classifies like Ernest's HTTP errors (401/403 configuration;
/// 408, 429 and 5xx retried; other 4xx dead-lettered). An optional bearer token
/// authenticates Prudence to the store. Outside dev/test the URL must be HTTPS.
@Component
@ConditionalOnExpression(
    "'${prudence.evidence.sink:ernest}' == 'cloudevents'"
        + " and '${prudence.evidence.cloudevents.transport:kafka}' == 'webhook'")
public class CloudEventsWebhookPublisher implements CloudEventsTransport {

  private final RestClient http;
  private final String url;
  private final String source;

  public CloudEventsWebhookPublisher(
      @Value("${prudence.evidence.cloudevents.webhook-url:}") String url,
      @Value("${prudence.evidence.cloudevents.webhook-token:}") String token,
      @Value("${prudence.evidence.cloudevents.source:prudence}") String source) {
    if (url == null || url.isBlank()) {
      throw new IllegalStateException(
          "prudence.evidence.cloudevents.transport=webhook needs"
              + " prudence.evidence.cloudevents.webhook-url");
    }
    URI uri = URI.create(url.strip());
    if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalStateException("webhook-url must be an http(s) URL (was " + url + ")");
    }
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3_000);
    factory.setReadTimeout(10_000);
    // Buffered, so each event goes out with a Content-Length: unbuffered, the JDK client streams
    // the body chunked, which many collectors (and simple HTTP servers) reject or reset.
    var builder =
        RestClient.builder().requestFactory(new BufferingClientHttpRequestFactory(factory));
    if (token != null && !token.isBlank()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.strip());
    }
    this.http = builder.build();
    this.url = uri.toString();
    this.source = source;
  }

  @Override
  public List<Exception> send(List<CloudEvent> events) {
    List<Exception> outcomes = new ArrayList<>(events.size());
    for (CloudEvent event : events) {
      try {
        http.post()
            .uri(url)
            .header(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE)
            .body(CloudEventsTransport.envelope(source, event))
            .retrieve()
            .toBodilessEntity();
        outcomes.add(null);
      } catch (Exception e) {
        outcomes.add(e);
      }
    }
    return outcomes;
  }

  /// The endpoint with any query string dropped (it may carry a credential).
  @Override
  public String target() {
    URI uri = URI.create(url);
    return uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
  }

  /// Any HTTP answer below 500 to a HEAD request means the store is there; a 5xx, a timeout
  /// or a refused connection means it is not.
  @Override
  public boolean reachable() {
    try {
      http.head().uri(url).retrieve().toBodilessEntity();
      return true;
    } catch (RestClientResponseException e) {
      return e.getStatusCode().value() < 500;
    } catch (Exception e) {
      return false;
    }
  }
}
