/**
 * One agent run, one flow id; one tool call, one governed pipeline.
 *
 * `run()` gives every decision taken inside it the same `correlationId` (Decision API v1): in
 * Prudence, and in the evidence each decision leaves in Ernest, the calls of one agent run then
 * read as one flow. Reports and execution evidence cite their decision, so they join the flow
 * through it.
 *
 *     await run(() => agent.generate("…"));                 // a fresh id
 *     await run("ticket-4711", () => agent.generate("…"));  // or your own business flow's id
 *
 * The id lives in an AsyncLocalStorage: it follows `await`, promises and timers started inside.
 *
 * `toolTarget()` is how every adapter names a tool call to Prudence (contract/adapter/): the
 * pipeline `tool:<name>` with the action `tools/call`, the same grant an AI or MCP gateway uses,
 * unless the integrator maps the tool to a pipeline of their own (then `run`).
 */
import { AsyncLocalStorage } from "node:async_hooks";
import { randomUUID } from "node:crypto";

export const TOOL_ACTION = "tools/call";
export const RUN_ACTION = "run";

const CORRELATION = /^[A-Za-z0-9._:-]{1,128}$/;
const current = new AsyncLocalStorage<string>();

/**
 * The id itself, or an Error: Prudence accepts only a plain token of at most 128 characters
 * (`[A-Za-z0-9._:-]`), and refuses the whole decision otherwise.
 */
export function checkCorrelationId(value: string): string {
  if (typeof value !== "string" || !CORRELATION.test(value)) {
    throw new Error("correlation id must match [A-Za-z0-9._:-]{1,128}");
  }
  return value;
}

/** The flow id of the enclosing `run()`, if any. */
export function currentCorrelationId(): string | undefined {
  return current.getStore();
}

/**
 * Run an agent as one flow: every decision inside carries the same `correlationId` (a new one
 * when not given). `fn` receives the id, e.g. to log it next to your own run.
 */
export function run<T>(
  fn: (correlationId: string) => T | Promise<T>,
): Promise<T>;
export function run<T>(
  correlationId: string,
  fn: (correlationId: string) => T | Promise<T>,
): Promise<T>;
export async function run<T>(
  first: string | ((correlationId: string) => T | Promise<T>),
  second?: (correlationId: string) => T | Promise<T>,
): Promise<T> {
  const fn = typeof first === "function" ? first : second;
  if (!fn) throw new Error("run() needs a function to run");
  const id =
    typeof first === "string"
      ? checkCorrelationId(first)
      : `run-${randomUUID()}`;
  return current.run(id, () => fn(id));
}

/**
 * `[pipeline, action]` a call to `tool` is governed as: `pipelines[tool]` with `run` when the
 * integrator mapped it, otherwise `tool:<tool>` with `tools/call`.
 */
export function toolTarget(
  tool: string,
  pipelines?: Readonly<Record<string, string>>,
): [string, string] {
  const mapped =
    pipelines && Object.hasOwn(pipelines, tool) ? pipelines[tool] : undefined;
  return mapped ? [mapped, RUN_ACTION] : [`tool:${tool}`, TOOL_ACTION];
}
