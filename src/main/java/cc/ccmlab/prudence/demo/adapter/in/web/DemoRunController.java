package cc.ccmlab.prudence.demo.adapter.in.web;

import cc.ccmlab.prudence.config.DemoRunnerProperties;
import cc.ccmlab.prudence.config.DemoRunnerProperties.Runner;
import cc.ccmlab.prudence.demo.adapter.out.records.DemoRunRecords;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Human-facing entry point for the governed framework demos. It aggregates the private demo runners
 * (integrations/demo-runner, protocol v1): the console lists the demos the configured runners
 * declare, a run goes to the runner that declared it, and a finished run carries what Prudence
 * itself recorded for it (the PDP decision and the reported execution), next to the runner's
 * account. The runners own the framework hooks and the M2M PDP and Ernest secrets.
 */
@RestController
@RequestMapping("/api/demos")
public class DemoRunController {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final int KEEP_RUNS = 500;

  private final DemoRunnerProperties properties;
  private final DemoRunRecords records;
  /// Runs started through this instance: which runner holds them and what to match them by.
  private final Map<String, StartedRun> runs =
      java.util.Collections.synchronizedMap(
          new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, StartedRun> eldest) {
              return size() > KEEP_RUNS;
            }
          });

  public DemoRunController(DemoRunnerProperties properties, DemoRunRecords records) {
    this.properties = properties;
    this.records = records;
  }

  /// The demos the configured runners declare; a runner that does not answer is left out.
  @GetMapping
  public List<Map<String, Object>> demos() {
    List<Map<String, Object>> all = new ArrayList<>();
    List<Runner> runners = properties.configured();
    for (int i = 0; i < runners.size(); i++) {
      for (Map<String, Object> demo : manifest(runners.get(i))) {
        if (all.stream().noneMatch(d -> d.get("id").equals(demo.get("id")))) {
          Map<String, Object> entry = new LinkedHashMap<>(demo);
          entry.put("runner", i);
          all.add(entry);
        }
      }
    }
    return all;
  }

  /// The latest run of each demo, from every runner, with Prudence's records.
  @GetMapping("/runs")
  public Map<String, Object> latest() {
    Map<String, Object> latest = new LinkedHashMap<>();
    for (Runner runner : properties.configured()) {
      try {
        Map<?, ?> body = client(runner, null).get().uri("/runs").retrieve().body(Map.class);
        if (body == null) continue;
        body.forEach(
            (demo, run) -> {
              if (run instanceof Map<?, ?> r) latest.putIfAbsent(String.valueOf(demo), enrich(r));
            });
      } catch (RuntimeException e) {
        // a missing optional demonstrator must not break readers
      }
    }
    return latest;
  }

  @PostMapping("/{demo}/runs")
  public ResponseEntity<?> run(
      @PathVariable String demo,
      @Valid @RequestBody DemoRunRequest request,
      Authentication authentication) {
    List<Runner> runners = properties.configured();
    if (runners.isEmpty()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "No governed demo runner is configured"));
    }
    Optional<Map<String, Object>> declared =
        demos().stream().filter(d -> demo.equals(d.get("id"))).findFirst();
    if (declared.isEmpty()) return ResponseEntity.notFound().build();

    Runner runner = runners.get((Integer) declared.get().get("runner"));
    String initiator = authentication == null ? "local-operator" : authentication.getName();
    Instant startedAt = Instant.now();
    try {
      Map<?, ?> started =
          client(runner, initiator)
              .post()
              .uri("/demos/{demo}/runs", demo)
              .body(Map.of("aiSystemId", request.aiSystemId()))
              .retrieve()
              .body(Map.class);
      if (started == null || !(started.get("runId") instanceof String runId)) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(Map.of("error", "Governed demo runner gave no run id"));
      }
      runs.put(
          runId,
          new StartedRun(
              runner,
              request.aiSystemId(),
              String.valueOf(declared.get().get("pipeline")),
              startedAt,
              new AtomicReference<>()));
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(started);
    } catch (RestClientResponseException e) {
      return ResponseEntity.status(e.getStatusCode())
          .header(HttpHeaders.CONTENT_TYPE, "application/json")
          .body(e.getResponseBodyAsString());
    } catch (RuntimeException e) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(Map.of("error", "Governed demo runner is unavailable"));
    }
  }

  /// One run's state; once finished, with what Prudence recorded for it.
  @GetMapping("/runs/{runId}")
  public ResponseEntity<?> run(@PathVariable String runId) {
    StartedRun started = runs.get(runId);
    if (started == null) return ResponseEntity.notFound().build();
    try {
      Map<?, ?> run =
          client(started.runner(), null)
              .get()
              .uri("/runs/{runId}", runId)
              .retrieve()
              .body(Map.class);
      return run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(enrich(run));
    } catch (RestClientResponseException e) {
      return ResponseEntity.status(e.getStatusCode())
          .body(Map.of("error", "Governed demo runner does not know this run"));
    } catch (RuntimeException e) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(Map.of("error", "Governed demo runner is unavailable"));
    }
  }

  private Map<String, Object> enrich(Map<?, ?> run) {
    Map<String, Object> out = new LinkedHashMap<>();
    run.forEach((k, v) -> out.put(String.valueOf(k), v));
    if ("running".equals(out.get("status"))) return out;
    StartedRun started = runs.get(String.valueOf(out.get("runId")));
    String system = started != null ? started.aiSystemId() : (String) out.get("aiSystemId");
    String pipeline = started != null ? started.pipeline() : (String) out.get("pipeline");
    String runtime = (String) out.get("demo"); // a demo's id is the runtime its adapter reports
    // The window is Prudence's own clock when this instance started the run: from the forward to
    // the first time it saw the run finished. Otherwise (after a restart) the runner's times.
    Instant from = started != null ? started.startedAt() : parse(out.get("startedAt"));
    Instant to =
        started != null
            ? started.finishedSeen().updateAndGet(seen -> seen != null ? seen : Instant.now())
            : Optional.ofNullable(parse(out.get("finishedAt"))).orElseGet(Instant::now);
    if (system != null && pipeline != null && runtime != null && from != null) {
      out.put("prudence", records.forRun(system, pipeline, runtime, from, to));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> manifest(Runner runner) {
    try {
      Map<?, ?> body = client(runner, null).get().uri("/demos").retrieve().body(Map.class);
      Object demos = body == null ? null : body.get("demos");
      if (!(demos instanceof List<?> list)) return List.of();
      return list.stream()
          .filter(d -> d instanceof Map<?, ?> m && m.get("id") instanceof String)
          .map(d -> (Map<String, Object>) d)
          .toList();
    } catch (RuntimeException e) {
      return List.of(); // a runner that is down offers nothing
    }
  }

  private static RestClient client(Runner runner, String initiator) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(TIMEOUT);
    RestClient.Builder builder =
        RestClient.builder()
            // Buffer the JSON body so it goes out with a Content-Length: the default JDK client
            // streams it chunked.
            .requestFactory(new BufferingClientHttpRequestFactory(factory))
            .baseUrl(runner.baseUrl().replaceAll("/+$", ""))
            .defaultHeader("X-Prudence-Demo-Runner-Token", runner.token());
    if (initiator != null) builder.defaultHeader("X-Prudence-Demo-Initiator", initiator);
    return builder.build();
  }

  private static Instant parse(Object at) {
    try {
      return at == null ? null : Instant.parse(String.valueOf(at));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private record StartedRun(
      Runner runner,
      String aiSystemId,
      String pipeline,
      Instant startedAt,
      AtomicReference<Instant> finishedSeen) {}

  public record DemoRunRequest(@NotBlank String aiSystemId) {}
}
