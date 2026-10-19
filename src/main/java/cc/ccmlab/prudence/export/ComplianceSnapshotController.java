package cc.ccmlab.prudence.export;

import cc.ccmlab.prudence.compliance.application.port.in.ComplianceControls;
import cc.ccmlab.prudence.registry.application.port.in.AiSystemQueries;
import cc.ccmlab.prudence.registry.application.port.in.ReviewQueries;
import cc.ccmlab.prudence.registry.domain.*;
import cc.ccmlab.prudence.shared.exception.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/// A saved manifest is immutable evidence of the state seen at generation time.
@RestController
@RequestMapping("/api/compliance-snapshots")
public class ComplianceSnapshotController {
  private final AiSystemQueries systems;
  private final ComplianceControls controls;
  private final ReviewQueries reviews;
  private final ComplianceSnapshotRepository snapshots;
  private final ObjectMapper json;

  public ComplianceSnapshotController(
      AiSystemQueries systems,
      ComplianceControls controls,
      ReviewQueries reviews,
      ComplianceSnapshotRepository snapshots,
      ObjectMapper json) {
    this.systems = systems;
    this.controls = controls;
    this.reviews = reviews;
    this.snapshots = snapshots;
    this.json = json;
  }

  @PostMapping("/systems/{id}")
  public StoredSnapshot create(@PathVariable String id) {
    var system =
        systems
            .findById(new AiSystemId(id))
            .orElseThrow(() -> new AiSystemNotFoundException(new AiSystemId(id)));
    String asOf = Instant.now().toString();
    var manifest = new TreeMap<String, Object>();
    manifest.put("schemaVersion", "prudence-compliance-snapshot/v1");
    manifest.put("asOf", asOf);
    manifest.put(
        "system",
        Map.of(
            "id",
            system.id().value(),
            "name",
            system.name(),
            "riskTier",
            system.riskTier().name(),
            "approvalStatus",
            system.approvalStatus().name(),
            "version",
            system.classification().systemVersion(),
            "modelHash",
            system.classification().modelHash(),
            "role",
            system.classification().regulatoryRole().name()));
    manifest.put("controls", controls.forSystem(id));
    manifest.put("reviews", reviews.reviewsForSystem(id));
    try {
      String encoded = json.writeValueAsString(manifest);
      String hash = sha(encoded);
      var saved =
          snapshots.save(
              new ComplianceSnapshotJpaEntity(
                  UUID.randomUUID().toString(), id, asOf, hash, encoded));
      return from(saved);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize compliance snapshot", e);
    }
  }

  @GetMapping("/{snapshotId}")
  public StoredSnapshot get(@PathVariable String snapshotId) {
    return from(
        snapshots
            .findById(snapshotId)
            .orElseThrow(
                () -> new DomainException("compliance snapshot not found: " + snapshotId)));
  }

  private StoredSnapshot from(ComplianceSnapshotJpaEntity s) {
    try {
      return new StoredSnapshot(
          s.getId(),
          s.getAiSystemId(),
          s.getAsOf(),
          s.getSha256(),
          json.readValue(s.getManifest(), Map.class));
    } catch (Exception e) {
      throw new IllegalStateException("stored snapshot is unreadable", e);
    }
  }

  private static String sha(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public record StoredSnapshot(
      String id, String aiSystemId, String asOf, String sha256, Map<String, Object> manifest) {}
}
