package com.prudence.evidence.delivery;

/// Driving port: deliver pending evidence of one kind right away, instead of waiting for the
/// next scheduled pass. Never throws — a failed attempt stays queued under the same contract.
/// Must be called outside a transaction (or after commit), so the record is visible.
public interface DeliverEvidence {
  void deliverNow(String target);
}
