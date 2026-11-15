/**
 * @chestertown/prudence-ai-sdk — Prudence governance for the Vercel AI SDK.
 *
 * The AI SDK has no hook in front of a tool, so the adapter wraps the tools you pass to
 * `generateText`, `streamText` or a `ToolLoopAgent`:
 *
 *     const tools = governTools({ sendCustomerLetter }, { aiSystemId: "68bf42c1-…" });
 *     await generateText({ model, tools, prompt: "Send the letter to customer C-1" });
 *
 * Before each call Prudence is asked about `tool:<tool name>` (action `tools/call`), or about
 * `pipelines[tool name]` (action `run`). Only ALLOW runs the tool; anything else (DENY,
 * APPROVAL_REQUIRED, Prudence unreachable) returns the refusal as the tool's result, so the
 * model reads why and the run goes on. Allowed calls are reported to Prudence under the SDK's
 * tool call id, and sealed in Ernest when `evidence` is given.
 *
 * A tool left out of `governTools` is not governed. Tools with no `execute` (client-side tools,
 * or tools the provider runs itself) are passed through unchanged: nothing runs here to gate;
 * govern those at an AI gateway.
 */
import { ToolGovernor, type GovernOptions } from "@chestertown/prudence-core";
import type { ToolSet } from "ai";

export {
  Decision,
  ErnestEvidence,
  M2MAuth,
  PrudenceGate,
  run,
  type GovernOptions,
} from "@chestertown/prudence-core";

type AnyFn = (...args: any[]) => any;

function isAsyncIterable(value: unknown): value is AsyncIterable<unknown> {
  return (
    value != null &&
    typeof (value as { [Symbol.asyncIterator]?: unknown })[
      Symbol.asyncIterator
    ] === "function"
  );
}

/**
 * The same tools, each asking Prudence before it runs. `options.runtime` defaults to "ai-sdk".
 * A streaming tool (one whose `execute` yields) is drained: its last value is the result.
 */
export function governTools<TOOLS extends ToolSet>(
  tools: TOOLS,
  options: GovernOptions,
): TOOLS {
  const governor = new ToolGovernor(options, "ai-sdk");
  const governed: Record<string, unknown> = {};
  for (const [name, original] of Object.entries(tools)) {
    const execute = (original as { execute?: AnyFn }).execute;
    if (typeof execute !== "function") {
      governed[name] = original;
      continue;
    }
    const refused = new Map<string, string>();
    const toModelOutput = (original as { toModelOutput?: AnyFn }).toModelOutput;
    governed[name] = {
      ...original,
      async execute(input: unknown, callOptions: { toolCallId?: string }) {
        const verdict = await governor.check(
          name,
          input,
          callOptions?.toolCallId,
        );
        if (!verdict.allowed) {
          if (callOptions?.toolCallId)
            refused.set(callOptions.toolCallId, verdict.refusal);
          return verdict.refusal;
        }
        const result = execute.call(original, input, callOptions);
        if (isAsyncIterable(result)) {
          let last: unknown;
          for await (const part of result) last = part;
          return last;
        }
        return await result;
      },
      // A tool that shapes its own output for the model would choke on a refusal: the model
      // gets the refusal as text instead.
      ...(typeof toModelOutput === "function" && {
        toModelOutput(args: { toolCallId: string; output: unknown }) {
          const refusal = refused.get(args.toolCallId);
          if (refusal !== undefined) {
            refused.delete(args.toolCallId);
            return { type: "text", value: refusal };
          }
          return toModelOutput.call(original, args);
        },
      }),
    };
  }
  return governed as TOOLS;
}
