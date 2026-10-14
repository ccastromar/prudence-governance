package com.prudence.posture.application.port.out;

import com.prudence.registry.domain.ApprovalStatus;
import com.prudence.registry.domain.RiskTier;
import java.util.List;

/// ACL over the registry context: posture sees only the governance facts it needs to colour
/// the estate, not the registry aggregate.
public interface SystemInventoryPort {
  List<GovernedSystem> all();

  /// `approvalExpiresAt` is null when not approved; `reviewDue` = renewal notice issued.
  record GovernedSystem(
      String id,
      String name,
      RiskTier riskTier,
      ApprovalStatus approvalStatus,
      String approvalExpiresAt,
      boolean reviewDue) {}
}
