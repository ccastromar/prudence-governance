package cc.ccmlab.prudence.causality;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.*;

@Service
public class CausalityService {
  private final SystemSequenceRepository sequences;

  public CausalityService(SystemSequenceRepository s) {
    sequences = s;
  }

  @Transactional
  public GovernanceEventEnvelope envelope(
      String type,
      String system,
      String version,
      String decision,
      String causation,
      String correlation,
      Map<String, Object> payload) {
    var seq =
        sequences.lock(system).orElseGet(() -> sequences.save(new SystemSequenceJpaEntity(system)));
    return new GovernanceEventEnvelope(
        UUID.randomUUID().toString(),
        type,
        Instant.now().toString(),
        system,
        version,
        decision,
        causation,
        correlation == null ? UUID.randomUUID().toString() : correlation,
        seq.take(),
        payload == null ? Map.of() : Map.copyOf(payload));
  }
}
