package com.prudence.registry.domain;

/// EU AI Act risk tiers. `annexIII` marks the high-risk uses listed in Annex III
/// (e.g. creditworthiness assessment of natural persons).
public enum RiskTier {
  PROHIBITED(false),
  HIGH(true),
  LIMITED(false),
  MINIMAL(false);

  private final boolean annexIII;

  RiskTier(boolean annexIII) {
    this.annexIII = annexIII;
  }

  public boolean isAnnexIII() {
    return annexIII;
  }

  /// High-risk and prohibited systems may not go live without an explicit governance decision.
  public boolean requiresApproval() {
    return this == HIGH || this == PROHIBITED;
  }
}
