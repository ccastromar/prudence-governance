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
/// with a system that is not registered, was never approved, or ran after its approval ended
/// is a policy-drift violation. Executions from while it was approved stay legitimate history.
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
        continue;
      }
      // Not approved now. Executions that all happened while it was still approved are
      // legitimate history; anything later, never-approved, or of unknown time is drift.
      Instant ended = parse(s.approvalEndedAt());
      Instant last = parse(scan.lastExecutionAt().get(s.id()));
      if (ended != null && last != null && !last.isAfter(ended)) {
        findings.add(
            new Finding(
                s.id(),
                s.name(),
                s.approvalStatus().name(),
                n,
                "OK",
                n
                    + " execution(s) on record, all while it was approved (latest "
                    + last
                    + "; approval ended "
                    + ended
                    + ")."));
        continue;
      }
      String why;
      if (ended == null) {
        why = "recorded for a system that was never approved";
      } else if (last == null) {
        why =
            "on record, but their times are unknown, so they cannot be shown to predate the end"
                + " of approval ("
                + ended
                + ")";
      } else {
        why = "recorded after the approval ended (latest " + last + "; ended " + ended + ")";
      }
      findings.add(
          new Finding(
              s.id(),
              s.name(),
              s.approvalStatus().name(),
              n,
              "VIOLATION",
              n + " execution(s) " + why + " — now " + s.approvalStatus() + ", ungoverned run."));
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

  private static Instant parse(String iso) {
    if (iso == null || iso.isBlank()) return null;
    try {
      return Instant.parse(iso);
    } catch (RuntimeException unparsable) {
      return null; // unknown: treated as not provably within the approval
    }
  }
}
