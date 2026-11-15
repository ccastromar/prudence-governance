package cc.ccmlab.prudence.governance;

import java.util.Map;
import java.util.Set;

/**
 * What every tool-call adapter is configured with.
 *
 * @param aiSystemId the registry id the agent acts as (or null with {@code aiSystems})
 * @param aiSystems one registry id per tool name; a tool with none is refused without asking
 * @param gate the PDP client (null: configured from the environment)
 * @param pipelines tool name → a pipeline of yours, asked with {@code run}; every other tool is
 *     asked as {@code tool:<name>} with {@code tools/call}
 * @param ungoverned tools that need no decision (read-only helpers); every other tool is governed
 * @param evidence with Ernest in the suite, also seal each allowed execution there (may be null)
 * @param runtime the runtime's name, sent as the subject and in reports (null: the adapter's)
 */
public record GovernOptions(
    String aiSystemId,
    Map<String, String> aiSystems,
    PrudenceGate gate,
    Map<String, String> pipelines,
    Set<String> ungoverned,
    ErnestEvidence evidence,
    String runtime) {

  public GovernOptions {
    aiSystems = aiSystems == null ? Map.of() : Map.copyOf(aiSystems);
    pipelines = pipelines == null ? Map.of() : Map.copyOf(pipelines);
    ungoverned = ungoverned == null ? Set.of() : Set.copyOf(ungoverned);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** One AI system for every tool, everything else from the environment. */
  public static GovernOptions of(String aiSystemId) {
    return builder().aiSystemId(aiSystemId).build();
  }

  public static final class Builder {
    private String aiSystemId;
    private Map<String, String> aiSystems;
    private PrudenceGate gate;
    private Map<String, String> pipelines;
    private Set<String> ungoverned;
    private ErnestEvidence evidence;
    private String runtime;

    public Builder aiSystemId(String aiSystemId) {
      this.aiSystemId = aiSystemId;
      return this;
    }

    public Builder aiSystems(Map<String, String> aiSystems) {
      this.aiSystems = aiSystems;
      return this;
    }

    public Builder gate(PrudenceGate gate) {
      this.gate = gate;
      return this;
    }

    public Builder pipelines(Map<String, String> pipelines) {
      this.pipelines = pipelines;
      return this;
    }

    public Builder ungoverned(Set<String> ungoverned) {
      this.ungoverned = ungoverned;
      return this;
    }

    public Builder evidence(ErnestEvidence evidence) {
      this.evidence = evidence;
      return this;
    }

    public Builder runtime(String runtime) {
      this.runtime = runtime;
      return this;
    }

    public GovernOptions build() {
      return new GovernOptions(
          aiSystemId, aiSystems, gate, pipelines, ungoverned, evidence, runtime);
    }
  }
}
