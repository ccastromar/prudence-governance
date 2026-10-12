package com.prudence.registry.application.port.out;

import com.prudence.registry.domain.ReviewEvent;

/// Driven port: seal a review event into the evidence plane (Ernest). Fail-soft — returns an
/// unsealed result when the evidence plane is unavailable; never blocks the review.
public interface ReviewSealPort {
    SealResult seal(ReviewEvent event);

    record SealResult(boolean sealed, Long blockIndex) {}
}
