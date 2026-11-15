package cc.ccmlab.prudence.governance;

/**
 * What an adapter does with one tool call: run it ({@link #allowed()}) or return {@link #refusal()}
 * to the model instead. {@code decision} is null for an ungoverned tool.
 */
public record ToolVerdict(
    boolean allowed,
    boolean ungoverned,
    Decision decision,
    String refusal,
    String pipeline,
    String executionId) {

  static ToolVerdict ungovernedTool() {
    return new ToolVerdict(true, true, null, null, null, null);
  }

  static ToolVerdict allow(Decision decision, String pipeline, String executionId) {
    return new ToolVerdict(true, false, decision, null, pipeline, executionId);
  }

  static ToolVerdict refuse(Decision decision, String refusal) {
    return new ToolVerdict(false, false, decision, refusal, null, null);
  }
}
