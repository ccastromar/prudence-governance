/** An OpenAI Agents SDK (JS) agent with one tool governed by @chestertown/prudence-openai-agents. */
import {
  Agent,
  OpenAIChatCompletionsModel,
  run,
  setTracingDisabled,
  tool,
} from "@openai/agents";
import { ErnestEvidence } from "@chestertown/prudence-core";
import { prudenceGuardrail } from "@chestertown/prudence-openai-agents";
import OpenAI from "openai";
import { z } from "zod";

import {
  AI_SYSTEM_ID,
  DESCRIPTION,
  LLM_API_KEY,
  LLM_MODEL,
  LLM_URL,
  PIPELINES,
  TASK,
  TOOL,
  env,
  finish,
  runTool,
} from "./story.js";

setTracingDisabled(true); // no traces to OpenAI from a test

const guard = prudenceGuardrail({
  aiSystemId: AI_SYSTEM_ID,
  ...(PIPELINES && { pipelines: PIPELINES }),
  ...(env.ERNEST_URL && { evidence: new ErnestEvidence() }),
});

const sendLetter = tool({
  name: TOOL,
  description: DESCRIPTION,
  parameters: z.object({ reference: z.string() }),
  execute: async ({ reference }) => runTool(reference),
  inputGuardrails: [guard],
});

const agent = new Agent({
  name: "claims_assistant",
  instructions: "Use your tools to do what the user asks.",
  tools: [sendLetter],
  model: new OpenAIChatCompletionsModel(
    new OpenAI({ baseURL: LLM_URL, apiKey: LLM_API_KEY }) as never,
    LLM_MODEL,
  ),
});

const result = await run(agent, TASK, { maxTurns: 4 });
finish(result.finalOutput);
