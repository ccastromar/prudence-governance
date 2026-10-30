package cc.ccmlab.prudence.posture.application.service;

import cc.ccmlab.prudence.posture.application.port.in.DriftQuery;
import cc.ccmlab.prudence.posture.application.port.out.ExecutionEvidencePort;
import cc.ccmlab.prudence.posture.application.port.out.SystemInventoryPort;
import cc.ccmlab.prudence.posture.domain.Drift;
import cc.ccmlab.prudence.posture.domain.Drift.Finding;
import cc.ccmlab.prudence.registry.domain.ApprovalStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/// Joins the registry with the evidence plane to catch ungoverned runs: an execution tagged
/// with a system that is not registered, was never approved, or ran after its approval ended
/// is a policy-drift violation. So is a reported execution no ALLOW decision covers, even of an
/// approved system: approval lets a system be asked about, not run without asking. Executions
/// from while it was approved, under an ALLOW, stay legitimate history.
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
      Map<String, Long> outside = scan.outsideDecision().getOrDefault(s.id(), Map.of());
      if (s.approvalStatus() == ApprovalStatus.APPROVED) {
        if (!outside.isEmpty()) {
          findings.add(outsideDecision(s, n, outside));
          continue;
        }
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
        if (!outside.isEmpty()) {
          findings.add(outsideDecision(s, n, outside));
          continue;
        }
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
              n
                  + " execution(s) "
                  + why
                  + " — now "
                  + s.approvalStatus()
                  + ", ungoverned run."
                  + (outside.isEmpty() ? "" : " " + describe(outside) + ".")));
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

  private static Finding outsideDecision(
      SystemInventoryPort.GovernedSystem s, long executions, Map<String, Long> outside) {
    return new Finding(
        s.id(),
        s.name(),
        s.approvalStatus().name(),
        executions,
        "VIOLATION",
        executions + " execution(s) on record: " + describe(outside) + ", ungoverned run.");
  }

  /// e.g. "1 ran after a DENY decision, 2 ran with no decision".
  private static String describe(Map<String, Long> outside) {
    return outside.entrySet().stream()
        .map(e -> e.getValue() + " ran " + statusPhrase(e.getKey()))
        .collect(Collectors.joining(", "));
  }

  private static String statusPhrase(String decisionStatus) {
    return switch (decisionStatus) {
      case "NONE" -> "with no decision";
      case "UNKNOWN" -> "citing a decision Prudence never recorded";
      case "OTHER_SYSTEM" -> "citing a decision about another system";
      default -> "after a " + decisionStatus + " decision";
    };
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
