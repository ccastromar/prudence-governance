package com.prudence.registry.domain;

import java.time.Instant;
import java.util.List;

/// Versioned, reviewable classification evidence. This is intentionally separate from the
/// risk tier: the tier is a conclusion; these facts are what let a reviewer challenge it.
public record ClassificationAssessment(
    RegulatoryRole regulatoryRole,
    String systemVersion,
    String modelHash,
    boolean annexI,
    boolean annexIII,
    String operatingGeography,
    String affectedPopulation,
    String justification,
    List<String> obligations,
    String assessedAt) {

  public ClassificationAssessment {
    if (regulatoryRole == null) throw new IllegalArgumentException("regulatory role required");
    require(systemVersion, "system version");
    require(modelHash, "model hash");
    require(operatingGeography, "operating geography");
    require(affectedPopulation, "affected population");
    require(justification, "classification justification");
    obligations = obligations == null ? List.of() : List.copyOf(obligations);
    if (obligations.isEmpty() || obligations.stream().anyMatch(o -> o == null || o.isBlank())) {
      throw new IllegalArgumentException("at least one applicable obligation required");
    }
    assessedAt = assessedAt == null || assessedAt.isBlank() ? Instant.now().toString() : assessedAt;
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
  }
}
