package cc.ccmlab.prudence.posture.application.service;

import cc.ccmlab.prudence.posture.application.port.in.PostureQuery;
import cc.ccmlab.prudence.posture.application.port.out.EvidenceDeliveryPort;
import cc.ccmlab.prudence.posture.application.port.out.EvidenceDeliveryPort.Backlog;
import cc.ccmlab.prudence.posture.application.port.out.EvidenceHealthPort;
import cc.ccmlab.prudence.posture.application.port.out.IncidentExposurePort;
import cc.ccmlab.prudence.posture.application.port.out.IncidentExposurePort.Exposure;
import cc.ccmlab.prudence.posture.application.port.out.SystemInventoryPort;
import cc.ccmlab.prudence.posture.domain.Posture;
import cc.ccmlab.prudence.posture.domain.Posture.PostureStatus;
import cc.ccmlab.prudence.posture.domain.Posture.SystemPosture;
import cc.ccmlab.prudence.registry.domain.ApprovalStatus;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/// Computes estate posture by joining the registry's governance facts with Ernest's evidence
/// health. The rules are deliberately conservative: a system is GREEN only when it is both
/// approved to run *and* backed by an intact, reachable evidence record — and none of its
/// governance records are stuck in, or dead-lettered from, the evidence outbox.
@Service
public class PostureService implements PostureQuery {

  private final SystemInventoryPort inventory;
  private final EvidenceHealthPort evidence;
  private final EvidenceDeliveryPort delivery;
  private final IncidentExposurePort incidents;

  public PostureService(
      SystemInventoryPort inventory,
      EvidenceHealthPort evidence,
      EvidenceDeliveryPort delivery,
      IncidentExposurePort incidents) {
    this.inventory = inventory;
    this.evidence = evidence;
    this.delivery = delivery;
    this.incidents = incidents;
  }

  @Override
  public Posture compute() {
    EvidenceHealthPort.EvidenceHealth health = evidence.check();
    Map<String, Backlog> backlog = delivery.undeliveredBySystem();
    Map<String, Exposure> exposure = incidents.bySystem();

    List<SystemInventoryPort.GovernedSystem> governed = inventory.all();
    // One lookup for every approved system (only they can be GREEN), not one per system.
    Set<String> withEvidence =
        !health.healthy()
            ? Set.of()
            : evidence.systemsWithEvidence(
                governed.stream()
                    .filter(s -> s.approvalStatus() == ApprovalStatus.APPROVED && !lapsed(s))
                    .map(SystemInventoryPort.GovernedSystem::id)
                    .toList());
    List<SystemPosture> systems =
        governed.stream()
            .map(
                s ->
                    withIncidents(
                        withDelivery(
                            score(s, health.healthy(), withEvidence.contains(s.id())),
                            backlog.get(s.id())),
                        exposure.get(s.id())))
            .toList();

    PostureStatus overall =
        systems.stream()
            .map(SystemPosture::status)
            .reduce(PostureStatus.GREEN, PostureService::worst);

    // Dead-lettered decisions for ids not in the registry belong to no row above, but they
    // are still missing evidence: they must not vanish from the estate view.
    Set<String> known = new HashSet<>();
    governed.forEach(s -> known.add(s.id()));
    long orphanDead =
        backlog.entrySet().stream()
            .filter(e -> !known.contains(e.getKey()))
            .mapToLong(e -> e.getValue().dead())
            .sum();
    String detail = health.detail();
    if (orphanDead > 0) {
      overall = PostureStatus.RED;
      detail =
          detail + " " + orphanDead + " dead-lettered decision record(s) for unregistered systems.";
    }

    return new Posture(overall, health.healthy(), detail, systems);
  }

  private SystemPosture score(
      SystemInventoryPort.GovernedSystem s, boolean evidenceHealthy, boolean hasEvidence) {
    PostureStatus status;
    String detail;

    switch (s.approvalStatus()) {
      case BLOCKED -> {
        status = PostureStatus.RED;
        detail = "Prohibited tier — blocked from use.";
      }
      case REJECTED -> {
        status = PostureStatus.RED;
        detail = "Rejected by governance review.";
      }
      case SUSPENDED -> {
        status = PostureStatus.RED;
        detail = "Suspended — must not run until reinstated.";
      }
      case RETIRED -> {
        status = PostureStatus.RED;
        detail = "Retired — must not run.";
      }
      case DRAFT, UNDER_ASSESSMENT, EVIDENCE_PENDING -> {
        status = PostureStatus.AMBER;
        detail = "Classification under assessment — not yet submitted for review.";
      }
      case PENDING_APPROVAL -> {
        status = PostureStatus.AMBER;
        detail = "Awaiting governance approval.";
      }
      case PENDING_SECOND_APPROVAL -> {
        status = PostureStatus.AMBER;
        detail = "First sign-off recorded; awaiting the independent second approval.";
      }
      case EXPIRED -> {
        status = PostureStatus.RED;
        detail = "Approval expired — resubmit for review before it can run.";
      }
      case APPROVED -> {
        if (lapsed(s)) {
          // The PDP already denies; the lifecycle sweep has not caught up yet.
          status = PostureStatus.RED;
          detail = "Approval expired " + s.approvalExpiresAt() + " — must not run.";
        } else if (!evidenceHealthy) {
          status = PostureStatus.AMBER;
          detail = "Approved, but the evidence layer is unavailable.";
        } else if (hasEvidence) {
          status = PostureStatus.GREEN;
          detail = "Approved, with evidence in the evidence store.";
        } else {
          // Approved and the chain is healthy, but nothing has been proved for this
          // system yet — a drift signal (no governed run on record).
          status = PostureStatus.AMBER;
          detail = "Approved, but no evidence recorded yet.";
        }
        if (status != PostureStatus.RED && s.reviewDue()) {
          status = PostureStatus.AMBER;
          detail = "Renewal review due — approval expires " + s.approvalExpiresAt() + ". " + detail;
        }
      }
      // Fail-closed: a lifecycle state posture does not know cannot be shown as safe.
      default -> {
        status = PostureStatus.RED;
        detail = "Unrecognised lifecycle state.";
      }
    }

    return new SystemPosture(
        s.id(), s.name(), s.riskTier().name(), s.approvalStatus().name(), status, detail);
  }

  private static boolean lapsed(SystemInventoryPort.GovernedSystem s) {
    return s.approvalExpiresAt() == null
        || !Instant.parse(s.approvalExpiresAt()).isAfter(Instant.now());
  }

  /// Overlays the outbox backlog: dead-lettered evidence is missing proof (RED); overdue
  /// evidence is proof that has not landed yet, so a system cannot be GREEN (AMBER).
  private static SystemPosture withDelivery(SystemPosture p, Backlog backlog) {
    if (backlog == null) return p;
    if (backlog.dead() > 0) {
      return new SystemPosture(
          p.id(),
          p.name(),
          p.riskTier(),
          p.approvalStatus(),
          PostureStatus.RED,
          backlog.dead()
              + " governance evidence record(s) (decisions/reviews/incidents) were rejected by"
              + " Ernest or ran out of retries — that evidence is missing. "
              + p.detail());
    }
    if (backlog.overdue() > 0) {
      return new SystemPosture(
          p.id(),
          p.name(),
          p.riskTier(),
          p.approvalStatus(),
          worst(p.status(), PostureStatus.AMBER),
          backlog.overdue()
              + " governance evidence record(s) (decisions/reviews/incidents) still awaiting"
              + " delivery to Ernest. "
              + p.detail());
    }
    return p;
  }

  /// Overlays the incident register: an overdue AI Act Art. 73 report is a compliance breach
  /// (RED); an open serious incident needs attention (AMBER at least).
  private static SystemPosture withIncidents(SystemPosture p, Exposure exposure) {
    if (exposure == null) return p;
    if (exposure.overdueReports() > 0) {
      return new SystemPosture(
          p.id(),
          p.name(),
          p.riskTier(),
          p.approvalStatus(),
          PostureStatus.RED,
          exposure.overdueReports()
              + " serious-incident report(s) overdue (AI Act Art. 73). "
              + p.detail());
    }
    return new SystemPosture(
        p.id(),
        p.name(),
        p.riskTier(),
        p.approvalStatus(),
        worst(p.status(), PostureStatus.AMBER),
        exposure.openSerious() + " open serious incident(s). " + p.detail());
  }

  /// Estate roll-up: the worst colour wins (one RED makes the estate RED).
  private static PostureStatus worst(PostureStatus a, PostureStatus b) {
    if (a == PostureStatus.RED || b == PostureStatus.RED) return PostureStatus.RED;
    if (a == PostureStatus.AMBER || b == PostureStatus.AMBER) return PostureStatus.AMBER;
    return PostureStatus.GREEN;
  }
}
