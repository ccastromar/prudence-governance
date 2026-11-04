package cc.ccmlab.prudence.policy.domain;

/// The PDP verdict as a sealed type — exhaustive pattern matching at the edges, no
/// stringly-typed statuses. Prudence decides; AOS (the PEP) enforces.
public sealed interface PolicyDecision
    permits PolicyDecision.Allow,
        PolicyDecision.Deny,
        PolicyDecision.ApprovalRequired,
        PolicyDecision.Error {

  record Allow() implements PolicyDecision {}

  /// `reasonCode` is a stable code a gate may act on (`contract/identity/` section 10), or null.
  record Deny(String reason, String reasonCode) implements PolicyDecision {
    public Deny(String reason) {
      this(reason, null);
    }
  }

  record Error(String reason) implements PolicyDecision {}

  record ApprovalRequired(String approver, String reason) implements PolicyDecision {}

  static PolicyDecision allow() {
    return new Allow();
  }

  static PolicyDecision deny(String reason) {
    return new Deny(reason);
  }

  static PolicyDecision deny(String reason, String reasonCode) {
    return new Deny(reason, reasonCode);
  }

  static PolicyDecision error(String reason) {
    return new Error(reason);
  }

  static PolicyDecision approval(String approver, String reason) {
    return new ApprovalRequired(approver, reason);
  }
}
