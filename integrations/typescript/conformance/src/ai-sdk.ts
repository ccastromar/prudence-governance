/** A Vercel AI SDK agent with one tool governed by @chestertown/prudence-ai-sdk. */
import { createOpenAI } from "@ai-sdk/openai";
import { ErnestEvidence } from "@chestertown/prudence-core";
import { governTools } from "@chestertown/prudence-ai-sdk";
import { generateText, stepCountIs, tool } from "ai";
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

const model = createOpenAI({ baseURL: LLM_URL, apiKey: LLM_API_KEY }).chat(
  LLM_MODEL,
);

const tools = governTools(
  {
    [TOOL]: tool({
      description: DESCRIPTION,
      inputSchema: z.object({ reference: z.string() }),
      execute: async ({ reference }) => runTool(reference),
    }),
  },
  {
    aiSystemId: AI_SYSTEM_ID,
    ...(PIPELINES && { pipelines: PIPELINES }),
    ...(env.ERNEST_URL && { evidence: new ErnestEvidence() }),
  },
);

const result = await generateText({
  model,
  system: "Use your tools to do what the user asks.",
  prompt: TASK,
  tools,
  stopWhen: stepCountIs(4),
});
finish(result.text);
