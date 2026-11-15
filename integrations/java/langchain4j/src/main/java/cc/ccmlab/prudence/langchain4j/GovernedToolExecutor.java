package cc.ccmlab.prudence.langchain4j;

import cc.ccmlab.prudence.governance.ToolGovernor;
import cc.ccmlab.prudence.governance.ToolVerdict;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.concurrent.CompletableFuture;

/**
 * A LangChain4j tool executor that asks Prudence before the one it wraps runs. Only ALLOW runs the
 * tool; anything else returns the refusal as the tool's result, so the model reads why. Every way
 * the AI service can run a tool (plain, with context, async) goes through the gate.
 */
public final class GovernedToolExecutor implements ToolExecutor {
  private final ToolExecutor delegate;
  private final ToolGovernor governor;

  GovernedToolExecutor(ToolExecutor delegate, ToolGovernor governor) {
    this.delegate = delegate;
    this.governor = governor;
  }

  private ToolVerdict check(ToolExecutionRequest request) {
    return governor.check(request.name(), request.arguments(), request.id());
  }

  private static ToolExecutionResult refused(String refusal) {
    return ToolExecutionResult.builder().resultText(refusal).build();
  }

  @Override
  public String execute(ToolExecutionRequest request, Object memoryId) {
    ToolVerdict verdict = check(request);
    return verdict.allowed() ? delegate.execute(request, memoryId) : verdict.refusal();
  }

  @Override
  public ToolExecutionResult executeWithContext(
      ToolExecutionRequest request, InvocationContext context) {
    ToolVerdict verdict = check(request);
    return verdict.allowed()
        ? delegate.executeWithContext(request, context)
        : refused(verdict.refusal());
  }

  @Override
  public CompletableFuture<ToolExecutionResult> executeAsync(
      ToolExecutionRequest request, InvocationContext context) {
    ToolVerdict verdict = check(request);
    return verdict.allowed()
        ? delegate.executeAsync(request, context)
        : CompletableFuture.completedFuture(refused(verdict.refusal()));
  }
}
