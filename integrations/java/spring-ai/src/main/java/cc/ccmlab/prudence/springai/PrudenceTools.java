package cc.ccmlab.prudence.springai;

import cc.ccmlab.prudence.governance.GovernOptions;
import cc.ccmlab.prudence.governance.ToolGovernor;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * Prudence governance for Spring AI: the tools you hand to a {@code ChatClient} or a chat model,
 * each asking Prudence first.
 *
 * <pre>{@code
 * ToolCallback[] tools = PrudenceTools.govern(GovernOptions.of("68bf42c1-…"), ToolCallbacks.from(new ClaimsTools()));
 * String answer = chatClient.prompt("Send the letter to customer C-1").toolCallbacks(tools).call().content();
 * }</pre>
 *
 * <p>Prudence is asked about {@code tool:<tool name>} (action {@code tools/call}), or about {@code
 * pipelines[tool name]} (action {@code run}). Only ALLOW runs the tool; anything else (DENY,
 * APPROVAL_REQUIRED, Prudence unreachable) returns the refusal as the tool's result. Allowed calls
 * are reported to Prudence, and sealed in Ernest when {@code evidence} is set. A tool left out is
 * not governed.
 */
public final class PrudenceTools {
  /** The runtime name sent when the options give none. */
  public static final String RUNTIME = "spring-ai";

  private PrudenceTools() {}

  /** The same tools, each asking Prudence before it runs (already governed ones are kept). */
  public static ToolCallback[] govern(GovernOptions options, ToolCallback... tools) {
    ToolGovernor governor = new ToolGovernor(options, RUNTIME);
    return Arrays.stream(tools)
        .map(t -> t instanceof GovernedToolCallback ? t : new GovernedToolCallback(t, governor))
        .toArray(ToolCallback[]::new);
  }

  public static List<ToolCallback> govern(
      GovernOptions options, List<? extends ToolCallback> tools) {
    return List.of(govern(options, tools.toArray(ToolCallback[]::new)));
  }

  /**
   * A provider whose tools are governed, e.g. the MCP client's {@code ToolCallbackProvider}: its
   * tools are read and wrapped each time they are asked for, so tools it adds later are governed.
   */
  public static ToolCallbackProvider govern(GovernOptions options, ToolCallbackProvider provider) {
    ToolGovernor governor = new ToolGovernor(options, RUNTIME);
    return () ->
        Arrays.stream(provider.getToolCallbacks())
            .map(t -> t instanceof GovernedToolCallback ? t : new GovernedToolCallback(t, governor))
            .toArray(ToolCallback[]::new);
  }
}
