package cc.ccmlab.prudence.registry.domain;

/// How many sign-offs a risk tier needs (one or two) and whether the second must come from a
/// different person. The organization sets it in its governance model; the legal floor keeps a
/// high-risk system at two distinct sign-offs.
public record ApprovalRule(int count, boolean distinct) {

  public ApprovalRule {
    if (count < 1 || count > 2) throw new IllegalArgumentException("approvals must be 1 or 2");
  }

  /// The rule before governance models: two distinct sign-offs for a high-risk system.
  public static ApprovalRule defaultFor(RiskTier tier) {
    return tier == RiskTier.HIGH ? new ApprovalRule(2, true) : new ApprovalRule(1, false);
  }
}
