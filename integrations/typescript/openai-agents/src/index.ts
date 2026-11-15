/**
 * @chestertown/prudence-openai-agents — Prudence governance for the OpenAI Agents SDK (JS).
 *
 * A tool input guardrail that asks Prudence before the tool runs:
 *
 *     const guard = prudenceGuardrail({ aiSystemId: "68bf42c1-…" });
 *     const sendLetter = tool({ name: "send_customer_letter", parameters, execute, inputGuardrails: [guard] });
 *     // or, for tools you did not define:
 *     govern(otherTool, guard);
 *
 * Prudence is asked about `tool:<tool name>` (action `tools/call`), or about
 * `pipelines[tool name]` (action `run`). Only ALLOW runs the tool. Anything else (DENY,
 * APPROVAL_REQUIRED, Prudence unreachable) rejects the call and the model reads the refusal;
 * `onDeny: "throw"` stops the run instead. Allowed calls are reported to Prudence under the
 * SDK's call id, and sealed in Ernest when `evidence` is given.
 */
import {
  ToolGuardrailFunctionOutputFactory,
  defineToolInputGuardrail,
  type FunctionTool,
  type ToolInputGuardrailDefinition,
} from "@openai/agents";
import { ToolGovernor, type GovernOptions } from "@chestertown/prudence-core";

export {
  Decision,
  ErnestEvidence,
  M2MAuth,
  PrudenceGate,
  run,
  type GovernOptions,
} from "@chestertown/prudence-core";

export interface GuardrailOptions extends GovernOptions {
  /** "reject" (default): skip the tool, tell the model, keep the run going. "throw": stop the run. */
  onDeny?: "reject" | "throw";
}

function parseArguments(raw: unknown): unknown {
  if (typeof raw !== "string") return raw ?? {};
  try {
    return JSON.parse(raw);
  } catch {
    return { arguments: raw };
  }
}

/** A tool input guardrail; `options.runtime` defaults to "openai-agents-js". */
export function prudenceGuardrail(
  options: GuardrailOptions,
): ToolInputGuardrailDefinition<any> {
  const governor = new ToolGovernor(options, "openai-agents-js");
  return defineToolInputGuardrail({
    name: "prudence",
    async run({ toolCall }) {
      const verdict = await governor.check(
        toolCall.name,
        parseArguments(toolCall.arguments),
        toolCall.callId,
      );
      if (verdict.allowed) {
        const info = verdict.ungoverned
          ? { decision: "UNGOVERNED" }
          : { decision: "ALLOW", decisionId: verdict.decision.decisionId };
        return ToolGuardrailFunctionOutputFactory.allow(info);
      }
      const { decision, reason, approver, decisionId } = verdict.decision;
      const info = { decision, reason, approver, decisionId };
      return options.onDeny === "throw"
        ? ToolGuardrailFunctionOutputFactory.throwException(info)
        : ToolGuardrailFunctionOutputFactory.rejectContent(
            verdict.refusal,
            info,
          );
    },
  });
}

/** Put the guardrail in front of a tool you did not define (it runs before the others). */
export function govern<T extends FunctionTool<any, any, any>>(
  target: T,
  guardrail: ToolInputGuardrailDefinition<any>,
): T {
  target.inputGuardrails = [guardrail, ...(target.inputGuardrails ?? [])];
  return target;
}
