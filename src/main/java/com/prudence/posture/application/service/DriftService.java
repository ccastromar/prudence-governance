package com.prudence.posture.application.service;

import com.prudence.posture.application.port.in.DriftQuery;
import com.prudence.posture.application.port.out.ExecutionEvidencePort;
import com.prudence.posture.application.port.out.SystemInventoryPort;
import com.prudence.posture.domain.Drift;
import com.prudence.posture.domain.Drift.Finding;
import com.prudence.registry.domain.ApprovalStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/// Joins the registry with the evidence plane to catch ungoverned runs: an execution tagged
/// with a system that is not APPROVED (or not registered at all) is a policy-drift violation.
@Service
public class DriftService implements DriftQuery {

  private final SystemInventoryPort inventory;
  private final ExecutionEvidencePort executions;

  public DriftService(SystemInventoryPort inventory, ExecutionEvidencePort executions) {
    this.inventory = inventory;
    this.executions = executions;
  }

  @Override
  public Drift compute() {
    ExecutionEvidencePort.ExecutionScan scan = executions.scan(); // scanned once
    Map<String, Long> counts = new HashMap<>(scan.counts());
    List<Finding> findings = new ArrayList<>();
    Set<String> known = new HashSet<>();

    for (SystemInventoryPort.GovernedSystem s : inventory.all()) {
      known.add(s.id());
      long n = counts.getOrDefault(s.id(), 0L);
      if (n == 0) continue; // only systems with runtime evidence are drift-relevant
      if (s.approvalStatus() == ApprovalStatus.APPROVED) {
        findings.add(
            new Finding(
                s.id(),
                s.name(),
                s.approvalStatus().name(),
                n,
                "OK",
                n + " governed execution(s) on record."));
      } else {
        findings.add(
            new Finding(
                s.id(),
                s.name(),
                s.approvalStatus().name(),
                n,
                "VIOLATION",
                n
                    + " execution(s) recorded for a non-approved system ("
                    + s.approvalStatus()
                    + ") — ungoverned run."));
      }
    }

    // Executions tagged with an id not in the registry at all: fully ungoverned.
    for (Map.Entry<String, Long> e : counts.entrySet()) {
      if (!known.contains(e.getKey())) {
        findings.add(
            new Finding(
                e.getKey(),
                "(unregistered)",
                "UNREGISTERED",
                e.getValue(),
                "VIOLATION",
                e.getValue() + " execution(s) for an unregistered AI system — ungoverned run."));
      }
    }

    boolean hasViolations = findings.stream().anyMatch(f -> "VIOLATION".equals(f.severity()));
    return new Drift(
        Instant.now().toString(), hasViolations, scan.complete(), scan.detail(), findings);
  }
}
