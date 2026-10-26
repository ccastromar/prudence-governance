package cc.ccmlab.prudence.evidence.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cc.ccmlab.prudence.causality.CausalityService;
import cc.ccmlab.prudence.causality.SystemSequenceJpaEntity;
import cc.ccmlab.prudence.causality.SystemSequenceRepository;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/// The decision a caller's flow asked for keeps that flow's correlation id on its way to
/// Ernest: the outbox must not replace it (contract/observability, section 3).
class OutboxEvidenceAdapterTest {

  private final EvidenceOutboxJpaRepository outbox = mock(EvidenceOutboxJpaRepository.class);
  private final SystemSequenceRepository sequences = mock(SystemSequenceRepository.class);
  private final OutboxEvidenceAdapter adapter =
      new OutboxEvidenceAdapter(outbox, new CausalityService(sequences));

  private static DecisionContext context(String riskTier, String correlationId) {
    return new DecisionContext(
        "aos",
        "support",
        "sys-1",
        "run",
        "v1",
        "sha",
        "1",
        "m",
        null,
        correlationId,
        null,
        "Support",
        riskTier,
        "d-1");
  }

  private EvidenceOutboxJpaEntity saved() {
    var captor = ArgumentCaptor.forClass(EvidenceOutboxJpaEntity.class);
    verify(outbox).save(captor.capture());
    return captor.getValue();
  }

  @Test
  void aRegisteredSystemsDecisionKeepsTheCallersCorrelationId() {
    when(sequences.lock(anyString())).thenReturn(Optional.of(new SystemSequenceJpaEntity("sys-1")));
    adapter.recordDecision(context("LIMITED", "task-4f1c2a5e"), new PolicyDecision.Allow());
    EvidenceOutboxJpaEntity record = saved();
    assertEquals("task-4f1c2a5e", record.getCorrelationId());
    assertNotNull(record.getSystemSequence());
  }

  @Test
  void anUnregisteredSystemsDecisionKeepsItToo() {
    adapter.recordDecision(context(null, "task-4f1c2a5e"), new PolicyDecision.Deny("unknown"));
    assertEquals("task-4f1c2a5e", saved().getCorrelationId());
  }

  @Test
  void withoutOneTheDecisionGetsAFreshCorrelationId() {
    when(sequences.lock(anyString())).thenReturn(Optional.empty());
    when(sequences.save(any())).thenAnswer(i -> i.getArgument(0));
    adapter.recordDecision(context("LIMITED", null), new PolicyDecision.Allow());
    assertNotNull(saved().getCorrelationId());
  }
}
