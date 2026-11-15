package cc.ccmlab.prudence.governance;

import java.util.UUID;

/**
 * The part every tool-call adapter shares (contract/adapter/): which tools are governed, what is
 * asked, the refusal the model reads, and the best-effort report and evidence after an ALLOW. An
 * adapter only puts {@link #check} at its framework's hook in front of a tool and does what the
 * verdict says.
 */
public final class ToolGovernor {
  private final GovernOptions options;
  private final PrudenceGate gate;
  private final String runtime;

  public ToolGovernor(GovernOptions options, String defaultRuntime) {
    this.options = options;
    this.gate = options.gate() != null ? options.gate() : new PrudenceGate();
    this.runtime = options.runtime() != null ? options.runtime() : defaultRuntime;
  }

  public String runtime() {
    return runtime;
  }

  /** The AI system a call to {@code tool} acts as, or null. */
  public String systemFor(String tool) {
    String system =
        options.aiSystemId() != null ? options.aiSystemId() : options.aiSystems().get(tool);
    return system == null || system.isBlank() ? null : system;
  }

  /**
   * Ask Prudence about one call to {@code tool}. Never throws: any failure is a refusal. On ALLOW
   * the execution is reported (and sealed in Ernest when configured) in the background, under
   * {@code callId} when the framework gives one.
   *
   * @param arguments the call's arguments (a JSON string or any object), hashed for the evidence
   */
  public ToolVerdict check(String tool, Object arguments, String callId) {
    try {
      if (options.ungoverned().contains(tool)) return ToolVerdict.ungovernedTool();
      String system = systemFor(tool);
      if (system == null) {
        // No AI system for this tool: there is nothing Prudence could have approved.
        return ToolVerdict.refuse(
            Decision.deny("no AI system for this tool"),
            "Not permitted by governance: tool \"" + tool + "\" has no AI system.");
      }
      ToolTarget target = ToolTarget.of(tool, options.pipelines());
      Decision decision = gate.decide(runtime, target.pipeline(), system, target.action());
      if (!decision.allowed()) return ToolVerdict.refuse(decision, decision.refusal());

      // Best effort, never blocks or fails the tool. One id for the report and the evidence, so
      // Prudence can match them.
      String executionId =
          callId == null || callId.isBlank() ? UUID.randomUUID().toString() : callId;
      Background.run(
          () -> gate.reportExecution(decision, system, target.pipeline(), runtime, executionId));
      ErnestEvidence evidence = options.evidence();
      if (evidence != null) {
        Object inputs = Json.tree(arguments);
        Background.run(
            () ->
                evidence.logExecution(
                    system,
                    target.pipeline(),
                    runtime,
                    decision.decisionId(),
                    inputs,
                    executionId));
      }
      return ToolVerdict.allow(decision, target.pipeline(), executionId);
    } catch (RuntimeException e) {
      Decision decision = Decision.deny("governance check failed: " + e.getMessage());
      return ToolVerdict.refuse(decision, decision.refusal());
    }
  }
}
