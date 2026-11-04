package cc.ccmlab.prudence.policy.domain;

import java.util.List;

/// Who asks, who acts and on whose behalf (`contract/identity/`). The caller always comes from
/// the authenticated token; the agent is the caller unless the caller names another one, which
/// it may do only for agents the policy lets it speak for; the person is never taken as proof
/// from the request body. `rejection` is set when the identities themselves already refuse the
/// request (a forwarded token that fails, a body that contradicts a token): the PDP records that
/// denial before looking at anything else. `agentRoles`: the roles in a verified agent's own token,
/// which managed policy decides by (null otherwise).
public record DecisionIdentity(
    String caller,
    String agent,
    AgentAssurance agentAssurance,
    Person onBehalfOf,
    Rejection rejection,
    List<String> agentRoles) {

  public DecisionIdentity {
    agentRoles = agentRoles == null ? null : List.copyOf(agentRoles);
  }

  public DecisionIdentity(
      String caller,
      String agent,
      AgentAssurance agentAssurance,
      Person onBehalfOf,
      Rejection rejection) {
    this(caller, agent, agentAssurance, onBehalfOf, rejection, null);
  }

  public enum AgentAssurance {
    /// The agent is the caller.
    SELF,
    /// The caller named the agent; valid only if the policy lets it speak for that agent.
    DELEGATED,
    /// A token Prudence validated names the agent.
    VERIFIED
  }

  public enum PersonAssurance {
    /// Prudence validated a token that names the person.
    VERIFIED,
    /// Named in the request without a token: recorded, never grants anything.
    ASSERTED
  }

  /// `functions`: the person's governance functions, resolved only from a verified token.
  public record Person(String id, PersonAssurance assurance, List<String> functions) {
    public Person {
      functions = functions == null ? List.of() : List.copyOf(functions);
    }

    public Person(String id, PersonAssurance assurance) {
      this(id, assurance, List.of());
    }
  }

  public record Rejection(String reason, String reasonCode) {}

  public DecisionIdentity(
      String caller, String agent, AgentAssurance agentAssurance, Person onBehalfOf) {
    this(caller, agent, agentAssurance, onBehalfOf, null, null);
  }

  /// Today's behaviour: the caller acts for itself, for no one in particular.
  public static DecisionIdentity self(String caller) {
    return new DecisionIdentity(caller, caller, AgentAssurance.SELF, null);
  }

  public boolean delegated() {
    return agentAssurance != AgentAssurance.SELF;
  }
}
