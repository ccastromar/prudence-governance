package com.prudence.registry.application.port.out;

import com.prudence.registry.domain.ReviewEvent;

/// Driven port: record a review event in the evidence plane (Ernest) under its id. Throws on
/// failure so the evidence delivery worker can classify and retry it; it is only ever called
/// from that worker, never from the review itself.
public interface ReviewSealPort {
  SealResult seal(ReviewEvent event) throws Exception;

  /// `qualified`: carries a QTSP seal (false when recorded without one).
  record SealResult(boolean qualified, Long blockIndex) {}
}
