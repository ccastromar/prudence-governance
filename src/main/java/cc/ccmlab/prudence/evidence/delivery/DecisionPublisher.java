package cc.ccmlab.prudence.evidence.delivery;

import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import java.util.List;

/// Where PDP decisions from the outbox go: the configured evidence store (ADR-001). Failures
/// propagate (single) or come back per item (batch) for the delivery worker to classify.
public interface DecisionPublisher {

  /// One decision waiting to be published, under its stable outbox id.
  record Item(String decisionId, DecisionContext context, PolicyDecision decision) {}

  /// Whether [#publishDecisions] sends real batches.
  boolean batchCapable();

  void publishDecision(String decisionId, DecisionContext context, PolicyDecision decision)
      throws Exception;

  /// One entry per input, in order: null when delivered, otherwise the failure.
  List<Exception> publishDecisions(List<Item> items);
}
