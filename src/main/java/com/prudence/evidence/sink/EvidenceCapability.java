package com.prudence.evidence.sink;

/// What an evidence store can guarantee for the records Prudence sends it (ADR-001).
/// CUSTODY is the floor: without it Prudence records nothing. The others are what lets a
/// third party check the record without trusting Prudence or the store's operator.
public enum EvidenceCapability {
  /// C1: durable acknowledgement and deduplication by event id.
  CUSTODY("Custody", "Records are durably acknowledged and deduplicated by event id."),
  /// C2: an inclusion proof for each record, verifiable offline.
  PROOF("Proof", "Each record has an inclusion proof that can be verified offline."),
  /// C3: an external anchor or qualified timestamp fixes when the records existed.
  TIME("Time", "An external anchor or timestamp fixes when the records existed."),
  /// C4: Prudence can read execution evidence and the store's health back.
  READ_BACK("Read-back", "Prudence can read execution evidence and the store's health back.");

  private final String label;
  private final String meaning;

  EvidenceCapability(String label, String meaning) {
    this.label = label;
    this.meaning = meaning;
  }

  public String label() {
    return label;
  }

  public String meaning() {
    return meaning;
  }
}
