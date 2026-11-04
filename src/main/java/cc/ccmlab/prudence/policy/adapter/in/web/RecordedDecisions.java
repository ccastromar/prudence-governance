package cc.ccmlab.prudence.policy.adapter.in.web;

import cc.ccmlab.prudence.config.ForwardedTokens;
import cc.ccmlab.prudence.config.IntegrationProperties;
import cc.ccmlab.prudence.policy.application.port.in.DecidePolicyUseCase;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.DecisionIdentity;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// How every PDP front door (Decision API v1, AuthZEN) asks for a decision: the id is assigned
/// here and the PDP records the decision under it in the same transaction, so the id returned
/// is always the id of the evidence, never one that was not stored.
final class RecordedDecisions {

  record Recorded(
      String decisionId,
      String policyVersion,
      String policyDigest,
      PolicyDecision decision,
      DecisionIdentity identity,
      String aiSystemId) {}

  private RecordedDecisions() {}

  /// The identities of a request (`contract/identity/` sections 3 and 4). `caller` is the
  /// authenticated identity; `agent` and `person` are what the request names, and the tokens
  /// what it forwards, if anything. A token that fails, or a name that contradicts a token,
  /// becomes a rejection the PDP records. Without a token issuer (open development profile)
  /// nothing could validate a forwarded token, so a request that sends one is refused (400).
  static DecisionIdentity identity(
      String caller,
      String agent,
      String person,
      String agentToken,
      String personToken,
      ForwardedTokens tokens) {
    boolean withAgentToken = present(agentToken);
    boolean withPersonToken = present(personToken);
    if ((withAgentToken || withPersonToken) && !tokens.available()) {
      throw new IllegalArgumentException(
          "forwarded tokens need a configured token issuer; none is configured");
    }
    String named = present(agent) ? agent.strip() : "";
    String asserted = present(person) ? person.strip() : "";
    DecisionIdentity.Rejection rejection = null;

    String agentId;
    DecisionIdentity.AgentAssurance assurance;
    List<String> agentRoles = null;
    if (withAgentToken) {
      try {
        var holder = tokens.verify(agentToken);
        agentId = holder.id();
        agentRoles = holder.roles();
        assurance =
            agentId.equals(caller)
                ? DecisionIdentity.AgentAssurance.SELF
                : DecisionIdentity.AgentAssurance.VERIFIED;
        if (!named.isEmpty() && !named.equals(agentId)) {
          rejection =
              new DecisionIdentity.Rejection(
                  "the request names agent " + named + " but the agent token names " + agentId,
                  "subject_mismatch");
        }
      } catch (ForwardedTokens.InvalidToken invalid) {
        agentId = named.isEmpty() ? caller : named;
        assurance =
            named.isEmpty() || named.equals(caller)
                ? DecisionIdentity.AgentAssurance.SELF
                : DecisionIdentity.AgentAssurance.DELEGATED;
        rejection =
            new DecisionIdentity.Rejection(
                "agent token rejected: " + invalid.getMessage(), "agent_token_invalid");
      }
    } else if (named.isEmpty() || named.equals(caller)) {
      agentId = caller;
      assurance = DecisionIdentity.AgentAssurance.SELF;
    } else {
      agentId = named;
      assurance = DecisionIdentity.AgentAssurance.DELEGATED;
    }

    DecisionIdentity.Person onBehalfOf = null;
    if (withPersonToken) {
      try {
        var holder = tokens.verify(personToken);
        onBehalfOf =
            new DecisionIdentity.Person(
                holder.id(), DecisionIdentity.PersonAssurance.VERIFIED, holder.functions());
        if (rejection == null && !asserted.isEmpty() && !asserted.equals(holder.id())) {
          rejection =
              new DecisionIdentity.Rejection(
                  "the request names " + asserted + " but the on-behalf-of token names another",
                  "subject_mismatch");
        }
      } catch (ForwardedTokens.InvalidToken invalid) {
        if (!asserted.isEmpty())
          onBehalfOf =
              new DecisionIdentity.Person(asserted, DecisionIdentity.PersonAssurance.ASSERTED);
        if (rejection == null)
          rejection =
              new DecisionIdentity.Rejection(
                  "on-behalf-of token rejected: " + invalid.getMessage(), "person_token_invalid");
      }
    } else if (!asserted.isEmpty()) {
      onBehalfOf = new DecisionIdentity.Person(asserted, DecisionIdentity.PersonAssurance.ASSERTED);
    }
    return new DecisionIdentity(caller, agentId, assurance, onBehalfOf, rejection, agentRoles);
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  /// The resolved identities as the wire shows them (`identity` in both APIs).
  static Map<String, Object> view(DecisionIdentity identity) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("caller", identity.caller());
    view.put("agent", identity.agent());
    view.put("agentAssurance", identity.agentAssurance().name().toLowerCase());
    if (identity.onBehalfOf() != null) {
      view.put(
          "onBehalfOf",
          Map.of(
              "id", identity.onBehalfOf().id(),
              "assurance", identity.onBehalfOf().assurance().name().toLowerCase()));
    }
    return view;
  }

  /// The subject of the decision is the agent that will act (`identity.agent()`).
  static Recorded decide(
      DecidePolicyUseCase pdp,
      IntegrationProperties properties,
      DecisionIdentity identity,
      String pipeline,
      String aiSystemId,
      String action,
      String correlationId) {
    String decisionId = UUID.randomUUID().toString();
    String policyVersion = properties.opa().policyVersion();
    var result =
        pdp.decideWithDetails(
            new DecisionContext(
                identity.agent(),
                pipeline,
                aiSystemId,
                action,
                policyVersion,
                properties.opa().policyHash(),
                "unknown",
                "unknown",
                null,
                correlationId == null || correlationId.isBlank() ? null : correlationId.strip(),
                null,
                null,
                null,
                decisionId,
                identity));
    return new Recorded(
        decisionId,
        result.policyVersion(),
        result.policyDigest(),
        result.decision(),
        identity,
        result.aiSystemId() != null ? result.aiSystemId() : aiSystemId);
  }
}
