/**
 * @chestertown/prudence-mastra — Prudence governance for Mastra.
 *
 * Agent tool hooks that ask Prudence before every tool call of the agent:
 *
 *     const agent = new Agent({ name, instructions, model, tools,
 *                               hooks: prudenceHooks({ aiSystemId: "68bf42c1-…" }) });
 *
 * Prudence is asked about `tool:<tool name>` (action `tools/call`), or about
 * `pipelines[tool name]` (action `run`). Only ALLOW runs the tool. Anything else (DENY,
 * APPROVAL_REQUIRED, Prudence unreachable) skips it and the model reads the refusal as the
 * tool's result. Allowed calls are reported to Prudence under the agent's tool call id, and
 * sealed in Ernest when `evidence` is given.
 *
 * The hooks govern every tool of the agent, tools added later included. Per-call hooks passed to
 * `generate` or `stream` override the agent's: pass `hooks` there only through
 * `withPrudence(prudence, yourHooks)`, so governance always runs first.
 */
import type { ToolHooks } from "@mastra/core/tools";
import { ToolGovernor, type GovernOptions } from "@chestertown/prudence-core";

export {
  Decision,
  ErnestEvidence,
  M2MAuth,
  PrudenceGate,
  run,
  type GovernOptions,
} from "@chestertown/prudence-core";

/** Tool hooks for `new Agent({ hooks })`; `options.runtime` defaults to "mastra". */
export function prudenceHooks(options: GovernOptions): ToolHooks {
  const governor = new ToolGovernor(options, "mastra");
  return {
    async beforeToolCall({ toolName, input, context }) {
      const callId = (
        context as { agent?: { toolCallId?: string } } | undefined
      )?.agent?.toolCallId;
      const verdict = await governor.check(toolName, input, callId);
      if (!verdict.allowed) return { proceed: false, output: verdict.refusal };
      return undefined;
    },
  };
}

/** Your own hooks behind Prudence's: Prudence decides first, yours run only if it allowed. */
export function withPrudence(
  prudence: ToolHooks,
  hooks: ToolHooks = {},
): ToolHooks {
  return {
    async beforeToolCall(context) {
      const verdict = await prudence.beforeToolCall?.(context);
      if (verdict) return verdict;
      return hooks.beforeToolCall?.(context);
    },
    ...(hooks.afterToolCall && { afterToolCall: hooks.afterToolCall }),
  };
}
