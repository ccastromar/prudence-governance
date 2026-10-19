package cc.ccmlab.prudence.evidence.adapter.out.cloudevents;

import cc.ccmlab.prudence.evidence.adapter.out.cloudevents.CloudEventsKafkaPublisher.CloudEvent;
import cc.ccmlab.prudence.evidence.adapter.out.ernest.ErnestSealer;
import cc.ccmlab.prudence.evidence.delivery.GovernanceRecordWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/// Governance records (incident events…) as `chestertown.prudence.record.v1` CloudEvents: the
/// register, the record id, the canonical hash of its content and its metadata. Custody only:
/// no seal, no block index.
@Component
@ConditionalOnProperty(prefix = "prudence.evidence", name = "sink", havingValue = "cloudevents")
public class CloudEventsRecordWriter implements GovernanceRecordWriter {

  static final String TYPE = "chestertown.prudence.record.v1";

  private final CloudEventsKafkaPublisher kafka;

  public CloudEventsRecordWriter(CloudEventsKafkaPublisher kafka) {
    this.kafka = kafka;
  }

  @Override
  public Receipt record(
      String registerId,
      String registerName,
      String recordId,
      Map<String, Object> content,
      Map<String, Object> metadata)
      throws Exception {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("register", Map.of("id", registerId, "name", registerName));
    data.put("recordId", recordId);
    data.put("contentHash", ErnestSealer.sha256Canonical(content));
    data.put("metadata", metadata);
    kafka.sendOne(new CloudEvent(recordId, TYPE, registerId, Instant.now(), data));
    return new Receipt(false, null);
  }
}
