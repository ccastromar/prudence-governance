package cc.ccmlab.prudence.registry.application.port.in;

import cc.ccmlab.prudence.registry.domain.ReviewEvent;
import java.util.List;

/// Driving port: read the governance review audit trail.
public interface ReviewQueries {
  List<ReviewEvent> reviewsForSystem(String aiSystemId);

  List<ReviewEvent> allReviews();
}
