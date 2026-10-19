package cc.ccmlab.prudence.evidence.adapter.out.cloudevents;

import cc.ccmlab.prudence.evidence.adapter.out.cloudevents.CloudEventsKafkaPublisher.CloudEvent;
import cc.ccmlab.prudence.registry.application.port.out.ReviewSealPort;
import cc.ccmlab.prudence.registry.domain.ReviewEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/// Review events (approve, first approval, reject) as `chestertown.prudence.review.v1`
/// CloudEvents. Recorded, never sealed: a custody-only store gives no seal and no block.
@Component
@ConditionalOnProperty(prefix = "prudence.evidence", name = "sink", havingValue = "cloudevents")
public class CloudEventsReviewSeal implements ReviewSealPort {

  static final String TYPE = "chestertown.prudence.review.v1";

  private final CloudEventsKafkaPublisher kafka;

  public CloudEventsReviewSeal(CloudEventsKafkaPublisher kafka) {
    this.kafka = kafka;
  }

  @Override
  public SealResult seal(ReviewEvent event) throws Exception {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("reviewId", event.id());
    data.put("aiSystem", Map.of("id", event.aiSystemId(), "systemOfRecord", "prudence"));
    data.put("action", event.action());
    data.put("reviewerHash", CloudEventsDecisionPublisher.sha256(event.reviewer()));
    if (event.reason() != null) data.put("reason", event.reason());
    data.put("at", event.at());
    data.put("previousStatus", event.previousStatus());
    data.put("newStatus", event.newStatus());
    kafka.sendOne(new CloudEvent(event.id(), TYPE, event.aiSystemId(), Instant.now(), data));
    return new SealResult(false, null);
  }
}
