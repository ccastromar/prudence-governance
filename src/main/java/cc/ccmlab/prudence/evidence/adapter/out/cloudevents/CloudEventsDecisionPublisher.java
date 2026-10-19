package cc.ccmlab.prudence.evidence.adapter.out.cloudevents;

import cc.ccmlab.prudence.evidence.adapter.out.cloudevents.CloudEventsTransport.CloudEvent;
import cc.ccmlab.prudence.evidence.delivery.DecisionPublisher;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/// PDP decisions as `chestertown.prudence.policy.decision.v1` CloudEvents (ADR-001 evidence
/// contract): the AI system's identity, the outcome and the policy that produced it. The
/// subject (who asked) travels only as a hash.
@Component
@ConditionalOnProperty(prefix = "prudence.evidence", name = "sink", havingValue = "cloudevents")
public class CloudEventsDecisionPublisher implements DecisionPublisher {

  static final String TYPE = "chestertown.prudence.policy.decision.v1";

  private final CloudEventsTransport transport;

  public CloudEventsDecisionPublisher(CloudEventsTransport transport) {
    this.transport = transport;
  }

  @Override
  public boolean batchCapable() {
    return true;
  }

  @Override
  public void publishDecision(String decisionId, DecisionContext context, PolicyDecision decision)
      throws Exception {
    transport.sendOne(event(decisionId, context, decision));
  }

  @Override
  public List<Exception> publishDecisions(List<Item> items) {
    return transport.send(
        items.stream().map(i -> event(i.decisionId(), i.context(), i.decision())).toList());
  }

  static CloudEvent event(String decisionId, DecisionContext ctx, PolicyDecision decision) {
    Map<String, Object> aiSystem = new LinkedHashMap<>();
    aiSystem.put("id", ctx.aiSystemId());
    if (ctx.aiSystemName() != null) aiSystem.put("name", ctx.aiSystemName());
    if (ctx.riskTier() != null) aiSystem.put("riskTier", ctx.riskTier());
    aiSystem.put("systemOfRecord", "prudence");

    Map<String, Object> outcome = new LinkedHashMap<>();
    outcome.put("decisionId", decisionId);
    switch (decision) {
      case PolicyDecision.Allow ignored -> outcome.put("outcome", "ALLOW");
      case PolicyDecision.Deny d -> {
        outcome.put("outcome", "DENY");
        outcome.put("reason", d.reason());
      }
      case PolicyDecision.ApprovalRequired a -> {
        outcome.put("outcome", "APPROVAL_REQUIRED");
        outcome.put("reason", a.reason());
        outcome.put("approver", a.approver());
      }
    }
    outcome.put("pipeline", ctx.pipeline());
    outcome.put("action", ctx.action());
    outcome.put("policyVersion", ctx.policyVersion());
    outcome.put("policyHash", ctx.policyHash());
    outcome.put("systemVersion", ctx.systemVersion());
    outcome.put("modelHash", ctx.modelHash());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("aiSystem", aiSystem);
    data.put("decision", outcome);
    data.put("subjectHash", sha256(ctx.subject()));
    if (ctx.correlationId() != null) data.put("correlationId", ctx.correlationId());
    if (ctx.causationId() != null) data.put("causationId", ctx.causationId());
    return new CloudEvent(decisionId, TYPE, ctx.aiSystemId(), Instant.now(), data);
  }

  static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
