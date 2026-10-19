package cc.ccmlab.prudence.posture.application.port.out;

import cc.ccmlab.prudence.registry.domain.ApprovalStatus;
import cc.ccmlab.prudence.registry.domain.RiskTier;
import java.util.List;

/// ACL over the registry context: posture sees only the governance facts it needs to colour
/// the estate, not the registry aggregate.
public interface SystemInventoryPort {
  List<GovernedSystem> all();

  /// `approvalExpiresAt` is null when not approved; `reviewDue` = renewal notice issued.
  /// `approvalEndedAt`: when the system last left APPROVED (suspended, expired…), from the
  /// append-only review trail; null if it was never approved and then taken out of approval.
  record GovernedSystem(
      String id,
      String name,
      RiskTier riskTier,
      ApprovalStatus approvalStatus,
      String approvalExpiresAt,
      boolean reviewDue,
      String approvalEndedAt) {}
}
