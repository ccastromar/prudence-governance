package cc.ccmlab.prudence.evidence.adapter.out.cloudevents;

import cc.ccmlab.prudence.attestation.application.port.out.AttestationEvidenceQueryPort;
import cc.ccmlab.prudence.attestation.application.port.out.AttestationSealPort;
import cc.ccmlab.prudence.evidence.adapter.out.cloudevents.CloudEventsKafkaPublisher.CloudEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/// Attestations as `chestertown.prudence.attestation.v1` CloudEvents (content hash and
/// summary). A custody-only store cannot be asked whether a record is present or its history
/// intact, so a confirmation is never claimed: verification reports it as not verifiable
/// (CUSTODY_ONLY), never as confirmed, and the receipt says why.
@Component
@ConditionalOnProperty(prefix = "prudence.evidence", name = "sink", havingValue = "cloudevents")
public class CloudEventsAttestationStore
    implements AttestationSealPort, AttestationEvidenceQueryPort {

  static final String TYPE = "chestertown.prudence.attestation.v1";

  private final CloudEventsKafkaPublisher kafka;

  public CloudEventsAttestationStore(CloudEventsKafkaPublisher kafka) {
    this.kafka = kafka;
  }

  @Override
  public Receipt seal(String attestationId, String contentHash, Map<String, Object> summary)
      throws Exception {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("attestationId", attestationId);
    data.put("contentHash", contentHash);
    data.put("summary", summary);
    kafka.sendOne(new CloudEvent(attestationId, TYPE, attestationId, Instant.now(), data));
    return new Receipt(
        false,
        null,
        "Held in an external evidence store ("
            + kafka.target()
            + ") with custody only: no seal and no inclusion proof, so it cannot be verified"
            + " independently of that store.");
  }

  @Override
  public Confirmation confirm(String modelId, String eventId, String contentHash) {
    return Confirmation.notVerifiable();
  }
}
