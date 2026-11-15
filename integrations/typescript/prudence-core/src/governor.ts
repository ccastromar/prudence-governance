/**
 * The part every tool-call adapter shares (contract/adapter/): which tools are governed, what
 * is asked, the refusal the model reads, and the best-effort report and evidence after an ALLOW.
 * An adapter only has to put `ToolGovernor.check` at its framework's hook in front of a tool and
 * do what the verdict says.
 */
import { randomUUID } from "node:crypto";

import { Decision } from "./decision.js";
import type { ErnestEvidence } from "./evidence.js";
import { PrudenceGate } from "./gate.js";
import { toolTarget } from "./run.js";

export interface GovernOptions {
  /** The registry id the agent acts as, or one per tool name. A tool with none is refused
   * without asking Prudence. */
  aiSystemId: string | Readonly<Record<string, string>>;
  /** Else a `PrudenceGate()` configured from the environment. */
  gate?: PrudenceGate;
  /** Tool name → a pipeline of yours (asked with `run`); every other tool is asked as
   * `tool:<name>` with `tools/call`. */
  pipelines?: Readonly<Record<string, string>>;
  /** Tools that need no decision (read-only helpers). Every other tool is governed. */
  ungoverned?: Iterable<string>;
  /** With Ernest in the suite, also seal each allowed execution there. */
  evidence?: ErnestEvidence;
  /** The runtime's name, sent as the subject and in reports. */
  runtime?: string;
}

export type ToolVerdict =
  | { allowed: true; ungoverned: true }
  | {
      allowed: true;
      ungoverned: false;
      decision: Decision;
      pipeline: string;
      executionId: string;
    }
  | { allowed: false; refusal: string; decision: Decision };

export class ToolGovernor {
  readonly gate: PrudenceGate;
  readonly runtime: string;
  private readonly aiSystemId: GovernOptions["aiSystemId"];
  private readonly pipelines: Readonly<Record<string, string>>;
  private readonly ungoverned: ReadonlySet<string>;
  private readonly evidence: ErnestEvidence | undefined;

  constructor(options: GovernOptions, defaultRuntime: string) {
    this.gate = options.gate ?? new PrudenceGate();
    this.aiSystemId = options.aiSystemId;
    this.pipelines = { ...(options.pipelines ?? {}) };
    this.ungoverned = new Set(options.ungoverned ?? []);
    this.evidence = options.evidence;
    this.runtime = options.runtime ?? defaultRuntime;
  }

  /** The AI system a call to `tool` acts as, if any. */
  systemFor(tool: string): string | undefined {
    const id = this.aiSystemId;
    const system =
      typeof id === "string"
        ? id
        : Object.hasOwn(id, tool)
          ? id[tool]
          : undefined;
    return system || undefined;
  }

  /**
   * Ask Prudence about one call to `tool`. Never throws: any failure is a refusal. On ALLOW the
   * execution is reported (and sealed in Ernest when configured) in the background, under
   * `callId` when the framework gives one.
   */
  async check(
    tool: string,
    input: unknown,
    callId?: string,
  ): Promise<ToolVerdict> {
    try {
      if (this.ungoverned.has(tool)) return { allowed: true, ungoverned: true };
      const system = this.systemFor(tool);
      if (!system) {
        // No AI system for this tool: there is nothing Prudence could have approved.
        const decision = new Decision("DENY", "no AI system for this tool");
        return {
          allowed: false,
          decision,
          refusal: `Not permitted by governance: tool ${JSON.stringify(tool)} has no AI system.`,
        };
      }
      const [pipeline, action] = toolTarget(tool, this.pipelines);
      const decision = await this.gate.decide(
        this.runtime,
        pipeline,
        system,
        action,
      );
      if (!decision.allowed)
        return { allowed: false, decision, refusal: decision.refusal() };

      // Best effort, never blocks or fails the tool. One id for the report and the evidence,
      // so Prudence can match them.
      const executionId = callId || randomUUID();
      void this.gate
        .reportExecution(decision, system, pipeline, {
          runtime: this.runtime,
          executionId,
        })
        .catch(() => false);
      if (this.evidence) {
        void this.evidence
          .logExecution(system, {
            pipeline,
            runtime: this.runtime,
            decisionId: decision.decisionId,
            inputs: input,
            executionId,
          })
          .catch(() => undefined);
      }
      return {
        allowed: true,
        ungoverned: false,
        decision,
        pipeline,
        executionId,
      };
    } catch (error) {
      const decision = new Decision(
        "DENY",
        `governance check failed: ${error instanceof Error ? error.message : String(error)}`,
      );
      return { allowed: false, decision, refusal: decision.refusal() };
    }
  }
}
