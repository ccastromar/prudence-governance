package com.prudence.policy.domain;

/// The PDP verdict as a sealed type — exhaustive pattern matching at the edges, no
/// stringly-typed statuses. Prudence decides; AOS (the PEP) enforces.
public sealed interface PolicyDecision
    permits PolicyDecision.Allow, PolicyDecision.Deny, PolicyDecision.ApprovalRequired {

  record Allow() implements PolicyDecision {}

  record Deny(String reason) implements PolicyDecision {}

  record ApprovalRequired(String approver, String reason) implements PolicyDecision {}

  static PolicyDecision allow() {
    return new Allow();
  }

  static PolicyDecision deny(String reason) {
    return new Deny(reason);
  }

  static PolicyDecision approval(String approver, String reason) {
    return new ApprovalRequired(approver, reason);
  }
}
