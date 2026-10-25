package cc.ccmlab.prudence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import cc.ccmlab.prudence.causality.SystemSequenceRepository;
import cc.ccmlab.prudence.evidence.adapter.out.persistence.EvidenceOutboxJpaRepository;
import cc.ccmlab.prudence.policy.application.port.in.DecidePolicyUseCase;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/// A PDP call for a system the registry does not know fails closed: it is answered DENY and the
/// DENY is recorded as evidence, without a per-system event sequence (on PostgreSQL that table
/// references ai_system, so taking one turned the decision into a 500).
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:prudence-it;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "prudence.ernest.base-url=http://127.0.0.1:1",
      "prudence.opa.base-url=http://127.0.0.1:1"
    })
@ActiveProfiles("test")
class UnknownSystemDecisionIntegrationTest {

  @Autowired private DecidePolicyUseCase pdp;
  @Autowired private EvidenceOutboxJpaRepository outbox;
  @Autowired private SystemSequenceRepository sequences;

  @Test
  void unknownSystemIsARecordedDenyWithoutASystemSequence() {
    String system = "not-registered-" + UUID.randomUUID();
    String decisionId = UUID.randomUUID().toString();

    var result =
        pdp.decideWithDetails(
            new DecisionContext(
                "pdp-client",
                "pipeline-1",
                system,
                "invoke",
                "v1",
                "hash",
                "unknown",
                "unknown",
                null,
                null,
                null,
                null,
                null,
                decisionId));

    assertEquals(new PolicyDecision.Deny("unknown AI system: " + system), result.decision());
    var recorded = outbox.findById(decisionId).orElseThrow();
    assertEquals("DENY", recorded.getVerdict());
    assertEquals(system, recorded.getAiSystemId());
    assertNull(recorded.getSystemSequence());
    assertFalse(sequences.existsById(system));
  }
}
