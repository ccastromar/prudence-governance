package cc.ccmlab.prudence.springai;

import cc.ccmlab.prudence.governance.ToolGovernor;
import cc.ccmlab.prudence.governance.ToolVerdict;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * A Spring AI tool that asks Prudence before the tool it wraps runs. Only ALLOW calls the tool;
 * anything else returns the refusal as the tool's result, so the model reads why.
 */
public final class GovernedToolCallback implements ToolCallback {
  private final ToolCallback delegate;
  private final ToolGovernor governor;

  GovernedToolCallback(ToolCallback delegate, ToolGovernor governor) {
    this.delegate = delegate;
    this.governor = governor;
  }

  /** The tool this one governs. */
  public ToolCallback delegate() {
    return delegate;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    ToolVerdict verdict = governor.check(getToolDefinition().name(), toolInput, null);
    return verdict.allowed() ? delegate.call(toolInput) : verdict.refusal();
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    ToolVerdict verdict = governor.check(getToolDefinition().name(), toolInput, null);
    return verdict.allowed() ? delegate.call(toolInput, toolContext) : verdict.refusal();
  }
}
