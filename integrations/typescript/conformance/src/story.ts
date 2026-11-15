/**
 * What every agent here shares: the run's settings from the environment (the conformance kit's
 * protocol, integrations/conformance-kit/README.md) and the one line it prints at the end.
 */
export const env = process.env;

/** The tool's name and what the agent is asked to do with it. */
export const TOOL = env.TOOL || env.DEMO_TOOL || "send_customer_letter";
export const DESCRIPTION =
  env.DEMO_TOOL_DESCRIPTION || "Send the standard claims letter to a customer.";
export const TASK = env.DEMO_TASK || "Send the letter to customer C-1";
export const RESULT = env.DEMO_RESULT || "sent to {reference}";

export const LLM_URL = env.LLM_URL || "http://127.0.0.1:8000/v1";
export const LLM_MODEL = env.LLM_MODEL || "conformance-scripted";
export const LLM_API_KEY = env.LLM_API_KEY || "none";

/** The AI system the agent acts as (empty: none, and every governed call is refused). */
export const AI_SYSTEM_ID = env.AI_SYSTEM_ID ?? "";

/** With PIPELINE set, the tool is mapped to that pipeline (asked with `run`); empty, it is asked
 * as `tool:<TOOL>` with `tools/call`. */
export const PIPELINES: Record<string, string> | undefined = env.PIPELINE
  ? { [TOOL]: env.PIPELINE }
  : undefined;

/** Calls of the tool's body: the kit checks that it ran only on ALLOW. */
export const ran: string[] = [];

export function runTool(reference: string): string {
  ran.push(reference);
  return RESULT.replace("{reference}", reference);
}

export function finish(answer: unknown): void {
  console.log(
    JSON.stringify({ toolRan: ran.length > 0, answer: String(answer ?? "") }),
  );
}
