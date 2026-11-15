/**
 * @chestertown/prudence-langgraph — Prudence governance for LangGraph.js.
 *
 * Two places to put the gate, as in the Python adapter:
 *
 * 1. In front of each tool, for `ToolNode`, `createReactAgent` or any LangChain tool caller:
 *
 *        const tools = governTools([sendCustomerLetter], { aiSystemId: "68bf42c1-…" });
 *        const graph = createReactAgent({ llm, tools });
 *
 *    Prudence is asked about `tool:<tool name>` (action `tools/call`), or about
 *    `pipelines[tool name]` (action `run`). Only ALLOW runs the tool; anything else returns the
 *    refusal as the tool's result, so the model reads why.
 *
 * 2. In front of a node (`governedNode` + `gateRoute`), when a graph step rather than a tool is
 *    the unit: the node asks about your pipeline, and the conditional edge stops the graph on
 *    anything but ALLOW.
 *
 * Allowed calls are reported to Prudence, and sealed in Ernest when `evidence` is given. A tool
 * left out of `governTools` is not governed.
 */
import { tool, type StructuredToolInterface } from "@langchain/core/tools";
import type { RunnableConfig } from "@langchain/core/runnables";
import { randomUUID } from "node:crypto";
import {
  ToolGovernor,
  PrudenceGate,
  type ErnestEvidence,
  type GovernOptions,
} from "@chestertown/prudence-core";

export {
  Decision,
  ErnestEvidence,
  M2MAuth,
  PrudenceGate,
  run,
  type GovernOptions,
} from "@chestertown/prudence-core";

/** The same tools, each asking Prudence before it runs; `options.runtime` defaults to "langgraph-js". */
export function governTools<T extends StructuredToolInterface>(
  tools: readonly T[],
  options: GovernOptions,
): StructuredToolInterface[] {
  const governor = new ToolGovernor(options, "langgraph-js");
  return tools.map(
    (original) =>
      tool(
        async (
          input: unknown,
          config?: RunnableConfig & { toolCall?: { id?: string } },
        ) => {
          const verdict = await governor.check(
            original.name,
            input,
            config?.toolCall?.id,
          );
          if (!verdict.allowed) return verdict.refusal;
          // The inner tool must not build its own ToolMessage: the outer one does.
          const { toolCall: _ignored, ...inner } = config ?? {};
          return original.invoke(input as never, inner);
        },
        {
          name: original.name,
          description: original.description,
          schema: original.schema as never,
          ...(original.returnDirect !== undefined && {
            returnDirect: original.returnDirect,
          }),
        },
      ) as unknown as StructuredToolInterface,
  );
}

export interface GovernedNodeOptions {
  /** The pipeline the node runs as (asked with `action`). */
  pipeline: string;
  action?: string;
  gate?: PrudenceGate;
  evidence?: ErnestEvidence;
  /** State keys holding the subject and the AI system (default "subject", "aiSystemId"). */
  subjectKey?: string;
  systemKey?: string;
  runtime?: string;
}

export interface GateUpdate {
  gate: string;
  reason?: string | null;
  output?: string;
}

/**
 * A node that asks Prudence before `fn` runs. It returns `{ gate: "ALLOW", ...fn's update }`, or
 * `{ gate, reason, output }` with the refusal and without running `fn`. A state with no AI system
 * is refused without asking. Route on it with `gateRoute`.
 */
export function governedNode<
  S extends Record<string, unknown>,
  U extends Record<string, unknown>,
>(
  fn: (state: S, config?: RunnableConfig) => U | Promise<U>,
  options: GovernedNodeOptions,
): (state: S, config?: RunnableConfig) => Promise<U | GateUpdate> {
  const gate = options.gate ?? new PrudenceGate();
  const runtime = options.runtime ?? "langgraph-js";
  const subjectKey = options.subjectKey ?? "subject";
  const systemKey = options.systemKey ?? "aiSystemId";
  return async (state, config) => {
    const system = state[systemKey];
    if (typeof system !== "string" || !system) {
      const reason = "the node has no AI system";
      return {
        gate: "DENY",
        reason,
        output: `Not permitted by governance: ${reason}.`,
      };
    }
    const subject =
      typeof state[subjectKey] === "string"
        ? (state[subjectKey] as string)
        : runtime;
    const decision = await gate.decide(
      subject,
      options.pipeline,
      system,
      options.action ?? "run",
    );
    if (!decision.allowed)
      return {
        gate: decision.decision,
        reason: decision.reason,
        output: decision.refusal(),
      };

    const executionId = randomUUID();
    void gate.reportExecution(decision, system, options.pipeline, {
      runtime,
      executionId,
    });
    if (options.evidence) {
      const inputs = Object.fromEntries(
        Object.entries(state).filter(
          ([k]) =>
            ![subjectKey, systemKey, "gate", "reason", "output"].includes(k),
        ),
      );
      void options.evidence.logExecution(system, {
        pipeline: options.pipeline,
        runtime,
        decisionId: decision.decisionId,
        inputs: { input: inputs },
        executionId,
      });
    }
    return { gate: "ALLOW", ...(await fn(state, config)) };
  };
}

/** Conditional-edge router: "allow" only when the gate allowed, "stop" otherwise. */
export function gateRoute(state: { gate?: unknown }): "allow" | "stop" {
  return state.gate === "ALLOW" ? "allow" : "stop";
}
