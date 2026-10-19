package com.prudence.evidence.sink;

import java.util.List;

/// The evidence store Prudence delivers to and what it guarantees. `missing` lists what this
/// installation cannot claim, so posture and attestations say it instead of implying it.
public record EvidenceSinkInfo(
    String kind, String label, String target, List<Level> capabilities, List<Level> missing) {

  public record Level(String code, String label, String meaning) {
    static Level of(EvidenceCapability c) {
      return new Level(c.name(), c.label(), c.meaning());
    }
  }
}
