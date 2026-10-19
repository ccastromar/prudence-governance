package cc.ccmlab.prudence.posture.adapter.out.registry;

import cc.ccmlab.prudence.posture.application.port.out.SystemInventoryPort;
import cc.ccmlab.prudence.registry.application.port.out.AiSystemRepository;
import cc.ccmlab.prudence.registry.application.port.out.ReviewLog;
import cc.ccmlab.prudence.registry.domain.ReviewEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/// ACL adapter: satisfies posture's inventory port by projecting the registry aggregate down
/// to the governance facts the posture view needs.
@Component
public class SystemInventoryAdapter implements SystemInventoryPort {

  private final AiSystemRepository registry;
  private final ReviewLog reviews;

  public SystemInventoryAdapter(AiSystemRepository registry, ReviewLog reviews) {
    this.registry = registry;
    this.reviews = reviews;
  }

  @Override
  public List<GovernedSystem> all() {
    Map<String, String> approvalEnded = approvalEndedAt();
    return registry.findAll().stream()
        .map(
            s ->
                new GovernedSystem(
                    s.id().value(),
                    s.name(),
                    s.riskTier(),
                    s.approvalStatus(),
                    s.approvalExpiresAt(),
                    s.reviewDue(),
                    approvalEnded.get(s.id().value())))
        .toList();
  }

  /// Latest transition out of APPROVED per system (suspend, expire, reject of a renewal…).
  /// Renewals (APPROVED -> APPROVED) do not end an approval.
  private Map<String, String> approvalEndedAt() {
    Map<String, Instant> latest = new HashMap<>();
    for (ReviewEvent e : reviews.findAll()) {
      if (!"APPROVED".equals(e.previousStatus()) || "APPROVED".equals(e.newStatus())) continue;
      Instant at;
      try {
        at = Instant.parse(e.at());
      } catch (RuntimeException unparsable) {
        continue;
      }
      latest.merge(e.aiSystemId(), at, (a, b) -> a.isAfter(b) ? a : b);
    }
    Map<String, String> out = new HashMap<>();
    latest.forEach((id, at) -> out.put(id, at.toString()));
    return out;
  }
}
