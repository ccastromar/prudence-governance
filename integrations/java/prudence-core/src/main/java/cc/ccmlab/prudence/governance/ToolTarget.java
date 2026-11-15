package cc.ccmlab.prudence.governance;

import java.util.Map;

/**
 * How every adapter names a tool call to Prudence (contract/adapter/): the pipeline {@code
 * tool:<name>} with the action {@code tools/call}, the same grant an AI or MCP gateway uses, unless
 * the integrator maps the tool to a pipeline of their own (then {@code run}).
 */
public record ToolTarget(String pipeline, String action) {
  public static final String TOOL_ACTION = "tools/call";
  public static final String RUN_ACTION = "run";

  public static ToolTarget of(String tool, Map<String, String> pipelines) {
    String mapped = pipelines == null ? null : pipelines.get(tool);
    return mapped != null && !mapped.isEmpty()
        ? new ToolTarget(mapped, RUN_ACTION)
        : new ToolTarget("tool:" + tool, TOOL_ACTION);
  }
}
