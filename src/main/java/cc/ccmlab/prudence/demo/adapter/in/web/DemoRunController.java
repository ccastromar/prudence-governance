package cc.ccmlab.prudence.demo.adapter.in.web;

import cc.ccmlab.prudence.config.DemoRunnerProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Human-facing entry point for the two framework demos. It is deliberately a relay: the Python
 * process executes the actual Pydantic AI/LangGraph hook and owns the M2M PDP and Ernest secrets.
 */
@RestController
@RequestMapping("/api/demos")
public class DemoRunController {
  private static final Map<String, String> RUNNERS =
      Map.of("bug-triage", "bug-triage", "response-drafter", "response-drafter");

  private final DemoRunnerProperties properties;

  public DemoRunController(DemoRunnerProperties properties) {
    this.properties = properties;
  }

  @GetMapping("/runs")
  public ResponseEntity<?> latest() {
    if (!properties.configured()) return ResponseEntity.ok(Map.of());
    try {
      Map<?, ?> result =
          RestClient.builder()
              .baseUrl(properties.baseUrl().replaceAll("/+$", ""))
              .defaultHeader("X-Prudence-Demo-Runner-Token", properties.token())
              .build()
              .get()
              .uri("/runs")
              .retrieve()
              .body(Map.class);
      return ResponseEntity.ok(result == null ? Map.of() : result);
    } catch (RuntimeException e) {
      return ResponseEntity.ok(Map.of()); // a missing optional demonstrator must not break readers
    }
  }

  @PostMapping("/{demo}/runs")
  public ResponseEntity<?> run(
      @PathVariable String demo,
      @Valid @RequestBody DemoRunRequest request,
      org.springframework.security.core.Authentication authentication) {
    String runner = RUNNERS.get(demo);
    if (runner == null) return ResponseEntity.notFound().build();
    if (!properties.configured()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "Governed demo runner is not configured"));
    }

    String initiator = authentication == null ? "local-operator" : authentication.getName();
    try {
      Map<?, ?> result =
          RestClient.builder()
              // Buffer the JSON body so it goes out with a Content-Length: the default JDK client
              // streams it chunked, and the runner's stdlib HTTP server then reads an empty body.
              .requestFactory(
                  new BufferingClientHttpRequestFactory(new JdkClientHttpRequestFactory()))
              .baseUrl(properties.baseUrl().replaceAll("/+$", ""))
              .defaultHeader("X-Prudence-Demo-Runner-Token", properties.token())
              .defaultHeader("X-Prudence-Demo-Initiator", initiator)
              .build()
              .post()
              .uri("/runs/{demo}", runner)
              .body(Map.of("aiSystemId", request.aiSystemId()))
              .retrieve()
              .body(Map.class);
      return ResponseEntity.ok(result == null ? Map.of() : result);
    } catch (RestClientResponseException e) {
      return ResponseEntity.status(e.getStatusCode())
          .header(HttpHeaders.CONTENT_TYPE, "application/json")
          .body(e.getResponseBodyAsString());
    } catch (RuntimeException e) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(Map.of("error", "Governed demo runner is unavailable"));
    }
  }

  public record DemoRunRequest(@NotBlank String aiSystemId) {}
}
