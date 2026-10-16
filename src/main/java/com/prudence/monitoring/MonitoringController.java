package com.prudence.monitoring;

import com.prudence.registry.application.port.in.*;
import com.prudence.registry.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/monitoring/signals")
public class MonitoringController {
  private final MonitoringSignalRepository repo;
  private final AiSystemQueries systems;
  private final ReviewAiSystemUseCase review;

  public MonitoringController(
      MonitoringSignalRepository r, AiSystemQueries s, ReviewAiSystemUseCase v) {
    repo = r;
    systems = s;
    review = v;
  }

  @PostMapping
  public MonitoringSignalJpaEntity ingest(@RequestBody Body b) {
    AiSystem s =
        systems
            .findById(new AiSystemId(b.aiSystemId))
            .orElseThrow(() -> new AiSystemNotFoundException(new AiSystemId(b.aiSystemId)));
    MonitoringStatus st =
        b.value >= b.redThreshold
            ? MonitoringStatus.RED
            : b.value >= b.amberThreshold ? MonitoringStatus.AMBER : MonitoringStatus.GREEN;
    if (st == MonitoringStatus.RED)
      review.suspend(s.id(), "monitoring", "RED " + b.signal + " signal");
    return repo.save(
        new MonitoringSignalJpaEntity(
            UUID.randomUUID().toString(),
            b.aiSystemId,
            b.signal,
            b.value,
            b.amberThreshold,
            b.redThreshold,
            st,
            Instant.now().toString(),
            b.sourceReference));
  }

  @GetMapping("/summary/{systemId}")
  public Summary summary(@PathVariable String systemId) {
    var all = repo.findByAiSystemIdOrderByObservedAtDesc(systemId);
    MonitoringStatus worst =
        all.stream()
            .map(MonitoringSignalJpaEntity::getStatus)
            .reduce(
                MonitoringStatus.GREEN,
                (a, b) ->
                    a == MonitoringStatus.RED || b == MonitoringStatus.RED
                        ? MonitoringStatus.RED
                        : a == MonitoringStatus.AMBER || b == MonitoringStatus.AMBER
                            ? MonitoringStatus.AMBER
                            : MonitoringStatus.GREEN);
    return new Summary(
        systemId,
        worst,
        all.size(),
        all.stream().filter(x -> x.getStatus() == MonitoringStatus.RED).count(),
        all.stream().filter(x -> x.getStatus() == MonitoringStatus.AMBER).count(),
        all);
  }

  public record Body(
      String aiSystemId,
      RiskSignal signal,
      double value,
      double amberThreshold,
      double redThreshold,
      String sourceReference) {}

  public record Summary(
      String aiSystemId,
      MonitoringStatus status,
      int observations,
      long redSignals,
      long amberSignals,
      java.util.List<MonitoringSignalJpaEntity> recentSignals) {}
}
