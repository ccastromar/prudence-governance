package com.prudence.posture.adapter.out.ernest;

import com.prudence.config.IntegrationProperties;
import com.prudence.posture.application.port.out.EvidenceHealthPort;
import com.prudence.posture.application.port.out.ExecutionEvidencePort;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/// ACL adapter over Ernest: reads GET /api/verify and reports whether the hashchain is intact
/// and reachable. Fail-closed — if Ernest cannot be reached or does not confirm validity,
/// evidence is reported as unhealthy rather than optimistically green. Also scans the chain
/// for runtime execution evidence, grouped by the AI system each run was tagged with.
@Component
public class ErnestEvidenceHealthAdapter implements EvidenceHealthPort, ExecutionEvidencePort {

  private static final Logger log = LoggerFactory.getLogger(ErnestEvidenceHealthAdapter.class);

  private final RestClient client;

  public ErnestEvidenceHealthAdapter(IntegrationProperties props) {
    RestClient.Builder builder =
        RestClient.builder().baseUrl(props.ernest().baseUrl()).requestFactory(timeoutFactory());
    String apiKey = props.ernest().apiKey();
    if (apiKey != null && !apiKey.isBlank()) {
      // Prudence authenticates to Ernest as a read client (ADR-002 caller identity).
      builder.defaultHeader("X-Ernest-Api-Key", apiKey);
    }
    this.client = builder.build();
  }

  @Override
  public EvidenceHealth check() {
    try {
      Map<?, ?> body = client.get().uri("/api/verify").retrieve().body(Map.class);
      boolean valid = body != null && Boolean.TRUE.equals(body.get("isValid"));
      // Ernest verifies incrementally (blocksVerified = new blocks since its checkpoint);
      // chainLength is the size of the chain. Older Ernest versions only report the former.
      Object blocks =
          body == null
              ? null
              : body.get("chainLength") != null
                  ? body.get("chainLength")
                  : body.get("blocksVerified");
      if (valid) {
        return new EvidenceHealth(
            true, "Hashchain verified" + (blocks != null ? " (" + blocks + " blocks)" : "") + ".");
      }
      return new EvidenceHealth(false, "Ernest reports the hashchain is not valid.");
    } catch (Exception e) {
      log.warn("could not read evidence health from Ernest: {}", e.getMessage());
      return new EvidenceHealth(false, "Ernest evidence layer is unreachable.");
    }
  }

  @Override
  public Set<String> systemsWithEvidence(Collection<String> aiSystemIds) {
    List<String> ids = List.copyOf(new LinkedHashSet<>(aiSystemIds));
    Set<String> found = new HashSet<>();
    try {
      for (int i = 0; i < ids.size(); i += 500) {
        List<String> chunk = ids.subList(i, Math.min(i + 500, ids.size()));
        Map<?, ?> body =
            client
                .get()
                .uri(
                    b ->
                        b.path("/api/stats/models")
                            .queryParam("ids", String.join(",", chunk))
                            .build())
                .retrieve()
                .body(Map.class);
        if (body != null && body.get("counts") instanceof Map<?, ?> counts) {
          counts.forEach(
              (k, v) -> {
                if (k instanceof String id && v instanceof Number n && n.longValue() > 0)
                  found.add(id);
              });
        }
      }
      return found;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        // Ernest without /api/stats/models: one integrity call per system.
        ids.stream().filter(this::hasEvidence).forEach(found::add);
        return found;
      }
      log.warn("could not read evidence counts from Ernest: {}", e.getMessage());
      return Set.of();
    } catch (Exception e) {
      log.warn("could not read evidence counts from Ernest: {}", e.getMessage());
      return Set.of();
    }
  }

  @Override
  public boolean hasEvidence(String aiSystemId) {
    try {
      Map<?, ?> body =
          client.get().uri("/api/models/{id}/integrity", aiSystemId).retrieve().body(Map.class);
      if (body == null) return false;
      boolean valid =
          Boolean.TRUE.equals(body.get("isValid")) || Boolean.TRUE.equals(body.get("valid"));
      Object total = body.get("totalBlocks");
      long blocks = total instanceof Number n ? n.longValue() : 0L;
      return valid && blocks >= 1;
    } catch (Exception e) {
      // 404 (no such model) or unreachable -> no evidence recorded for this system yet.
      return false;
    }
  }

  @Override
  public ExecutionScan scan() {
    // One server-side aggregation instead of paging the whole chain. Ernest versions
    // without the endpoint (404) fall back to paging.
    try {
      Map<?, ?> body = client.get().uri("/api/stats/executions").retrieve().body(Map.class);
      Map<String, Long> counts = new HashMap<>();
      Map<String, String> lastExecutionAt = new HashMap<>();
      if (body != null && body.get("counts") instanceof Map<?, ?> c) {
        c.forEach(
            (k, v) -> {
              if (k instanceof String id && v instanceof Number n) counts.put(id, n.longValue());
            });
      }
      // Older Ernest versions do not report it; missing times stay unknown (never "early").
      if (body != null && body.get("lastExecutionAt") instanceof Map<?, ?> l) {
        l.forEach(
            (k, v) -> {
              if (k instanceof String id && v instanceof String at) lastExecutionAt.put(id, at);
            });
      }
      return new ExecutionScan(counts, lastExecutionAt, true, null);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() != 404) {
        log.warn("could not read execution counts from Ernest: {}", e.getMessage());
        return new ExecutionScan(
            Map.of(), false, "Execution counts unavailable: " + e.getMessage());
      }
    } catch (Exception e) {
      log.warn("could not read execution counts from Ernest: {}", e.getMessage());
      return new ExecutionScan(Map.of(), false, "Execution counts unavailable: " + e.getMessage());
    }
    return scanByPaging();
  }

  /// Fallback for Ernest versions without /api/stats/executions.
  private ExecutionScan scanByPaging() {
    Map<String, Long> counts = new HashMap<>();
    Map<String, java.time.Instant> latest = new HashMap<>();
    try {
      int page = 1, totalPages = 1;
      do {
        Map<?, ?> body =
            client.get().uri("/api/blocks?page={p}&limit=200", page).retrieve().body(Map.class);
        if (body == null) break;
        if (body.get("totalPages") instanceof Number n) totalPages = n.intValue();
        if (body.get("items") instanceof List<?> items) {
          for (Object it : items) {
            if (it instanceof Map<?, ?> blk
                && blk.get("data") instanceof Map<?, ?> data
                && data.get("metadata") instanceof Map<?, ?> md
                && "execution".equals(md.get("stage"))
                && md.get("aiSystemId") instanceof String sid
                && !sid.isBlank()) {
              counts.merge(sid, 1L, Long::sum);
              java.time.Instant at = executionTime(data.get("occurredAt"), blk.get("timestamp"));
              if (at != null) latest.merge(sid, at, (a, b) -> a.isAfter(b) ? a : b);
            }
          }
        }
        page++;
      } while (page <= totalPages);
    } catch (Exception e) {
      // Fail-closed: report what was read, flagged incomplete, never as a full scan.
      log.warn("could not scan execution evidence from Ernest: {}", e.getMessage());
      return new ExecutionScan(
          counts, isoTimes(latest), false, "Evidence scan stopped early: " + e.getMessage());
    }
    return new ExecutionScan(counts, isoTimes(latest), true, null);
  }

  /// The event's stated time when it parses, else the block's own time (epoch millis).
  private static java.time.Instant executionTime(Object occurredAt, Object blockTimestamp) {
    if (occurredAt instanceof String s && !s.isBlank()) {
      try {
        return java.time.Instant.parse(s);
      } catch (RuntimeException ignored) {
        // fall back to the block time
      }
    }
    return blockTimestamp instanceof Number n ? java.time.Instant.ofEpochMilli(n.longValue()) : null;
  }

  private static Map<String, String> isoTimes(Map<String, java.time.Instant> times) {
    Map<String, String> out = new HashMap<>();
    times.forEach((id, at) -> out.put(id, at.toString()));
    return out;
  }

  private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory() {
    var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    f.setConnectTimeout(Duration.ofSeconds(2));
    f.setReadTimeout(Duration.ofSeconds(3));
    return f;
  }
}
