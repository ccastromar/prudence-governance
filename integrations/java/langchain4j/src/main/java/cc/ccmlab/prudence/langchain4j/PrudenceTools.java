package cc.ccmlab.prudence.langchain4j;

import cc.ccmlab.prudence.governance.GovernOptions;
import cc.ccmlab.prudence.governance.ToolGovernor;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prudence governance for LangChain4j: tools for an AI service, each asking Prudence first.
 *
 * <pre>{@code
 * Assistant assistant = AiServices.builder(Assistant.class)
 *     .chatModel(model)
 *     .tools(PrudenceTools.govern(GovernOptions.of("68bf42c1-…"), new ClaimsTools()))
 *     .build();
 * }</pre>
 *
 * <p>Prudence is asked about {@code tool:<tool name>} (action {@code tools/call}), or about {@code
 * pipelines[tool name]} (action {@code run}). Only ALLOW runs the tool; anything else (DENY,
 * APPROVAL_REQUIRED, Prudence unreachable) returns the refusal as the tool's result. Allowed calls
 * are reported to Prudence under the call's id, and sealed in Ernest when {@code evidence} is set.
 * Pass every tool through here: a tool given to the AI service directly is not governed.
 */
public final class PrudenceTools {
  /** The runtime name sent when the options give none. */
  public static final String RUNTIME = "langchain4j";

  private PrudenceTools() {}

  /** The {@code @Tool} methods of {@code toolObjects}, each asking Prudence before it runs. */
  public static Map<ToolSpecification, ToolExecutor> govern(
      GovernOptions options, Object... toolObjects) {
    Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
    for (Object object : toolObjects) {
      for (Method method : object.getClass().getDeclaredMethods()) {
        if (method.isAnnotationPresent(Tool.class)) {
          tools.put(
              ToolSpecifications.toolSpecificationFrom(method),
              new DefaultToolExecutor(object, method));
        }
      }
    }
    return govern(options, tools);
  }

  /** The same tools, each executor asking Prudence before it runs. */
  public static Map<ToolSpecification, ToolExecutor> govern(
      GovernOptions options, Map<ToolSpecification, ToolExecutor> tools) {
    ToolGovernor governor = new ToolGovernor(options, RUNTIME);
    Map<ToolSpecification, ToolExecutor> governed = new LinkedHashMap<>();
    tools.forEach((spec, executor) -> governed.put(spec, wrap(executor, governor)));
    return governed;
  }

  /**
   * A tool provider whose tools are governed, e.g. an MCP tool provider: every tool it provides, on
   * every request, goes through the gate.
   */
  public static ToolProvider govern(GovernOptions options, ToolProvider provider) {
    ToolGovernor governor = new ToolGovernor(options, RUNTIME);
    return new ToolProvider() {
      @Override
      public ToolProviderResult provideTools(
          dev.langchain4j.service.tool.ToolProviderRequest request) {
        ToolProviderResult result = provider.provideTools(request);
        ToolProviderResult.Builder governed = ToolProviderResult.builder();
        result
            .aiServiceTools()
            .forEach(
                t ->
                    governed.add(
                        t.toolSpecification(),
                        wrap(t.toolExecutor(), governor),
                        t.returnBehavior()));
        return governed.build();
      }

      @Override
      public boolean isDynamic() {
        return provider.isDynamic();
      }
    };
  }

  private static ToolExecutor wrap(ToolExecutor executor, ToolGovernor governor) {
    return executor instanceof GovernedToolExecutor
        ? executor
        : new GovernedToolExecutor(executor, governor);
  }
}
