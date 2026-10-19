package cc.ccmlab.prudence.posture.adapter.out.executions;

import cc.ccmlab.prudence.evidence.adapter.out.cloudevents.CloudEventsTransport;
import cc.ccmlab.prudence.evidence.adapter.out.persistence.EvidenceOutboxJpaRepository;
import cc.ccmlab.prudence.posture.application.port.out.EvidenceHealthPort;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/// Evidence health over a custody-only store (ADR-001 C1). Prudence cannot verify the records'
/// integrity there, so "healthy" means only what it can know — the store is reachable — and
/// the detail says what it cannot. A system "has evidence" when at least one of its decisions
/// was acknowledged by the store (custody, not proof).
@Component
@ConditionalOnProperty(prefix = "prudence.evidence", name = "sink", havingValue = "cloudevents")
public class CloudEventsEvidenceHealth implements EvidenceHealthPort {

  private final CloudEventsTransport transport;
  private final EvidenceOutboxJpaRepository outbox;

  public CloudEventsEvidenceHealth(
      CloudEventsTransport transport, EvidenceOutboxJpaRepository outbox) {
    this.transport = transport;
    this.outbox = outbox;
  }

  @Override
  public EvidenceHealth check() {
    if (!transport.reachable()) {
      return new EvidenceHealth(
          false, "External evidence store (" + transport.target() + ") is unreachable.");
    }
    return new EvidenceHealth(
        true,
        "External evidence store ("
            + transport.target()
            + ") reachable. Custody only: Prudence cannot verify the records' integrity there.");
  }

  @Override
  public boolean hasEvidence(String aiSystemId) {
    return !systemsWithEvidence(Set.of(aiSystemId)).isEmpty();
  }

  @Override
  public Set<String> systemsWithEvidence(Collection<String> aiSystemIds) {
    if (aiSystemIds.isEmpty()) return Set.of();
    try {
      return new HashSet<>(outbox.deliveredSystems(aiSystemIds));
    } catch (RuntimeException e) {
      return Set.of(); // fail-closed
    }
  }
}
