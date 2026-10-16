package com.prudence.monitoring;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/monitoring/capas")
public class CapaController {
  private final CapaRepository repo;
  private final MonitoringSignalRepository signals;

  public CapaController(CapaRepository r, MonitoringSignalRepository s) {
    repo = r;
    signals = s;
  }

  @GetMapping("/systems/{id}")
  public List<CapaJpaEntity> list(@PathVariable String id) {
    return repo.findByAiSystemIdOrderByDueAtAsc(id);
  }

  @PostMapping
  public CapaJpaEntity open(@RequestBody Open b) {
    var signal =
        signals
            .findById(b.signalId)
            .orElseThrow(() -> new IllegalArgumentException("signal not found"));
    if (b.risk == null || b.mitigation == null || b.owner == null || b.dueAt == null)
      throw new IllegalArgumentException("risk, mitigation, owner and due date required");
    return repo.save(
        new CapaJpaEntity(
            UUID.randomUUID().toString(),
            signal.getAiSystemId(),
            signal.getId(),
            b.risk,
            b.mitigation,
            b.owner,
            b.dueAt));
  }

  @PostMapping("/{id}/implement")
  public CapaJpaEntity implement(@PathVariable String id) {
    var c = repo.findById(id).orElseThrow();
    c.implement();
    return repo.save(c);
  }

  @PostMapping("/{id}/verify")
  public CapaJpaEntity verify(@PathVariable String id, @RequestBody Verify b) {
    if (b.evidenceReference == null || b.evidenceReference.isBlank())
      throw new IllegalArgumentException("effectiveness evidence required");
    var c = repo.findById(id).orElseThrow();
    c.verify(b.evidenceReference);
    return repo.save(c);
  }

  public record Open(String signalId, String risk, String mitigation, String owner, String dueAt) {}

  public record Verify(String evidenceReference) {}
}
