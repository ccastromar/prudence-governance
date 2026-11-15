/** A Mastra agent with one tool governed by @chestertown/prudence-mastra. */
import { Agent } from "@mastra/core/agent";
import { createTool } from "@mastra/core/tools";
import { ErnestEvidence } from "@chestertown/prudence-core";
import { prudenceHooks } from "@chestertown/prudence-mastra";
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

const sendLetter = createTool({
  id: TOOL,
  description: DESCRIPTION,
  inputSchema: z.object({ reference: z.string() }),
  execute: async ({ reference }) => runTool(reference),
});

const agent = new Agent({
  id: "claims-assistant",
  name: "claims_assistant",
  instructions: "Use your tools to do what the user asks.",
  // Mastra's own OpenAI-compatible client, pointed at the run's model.
  model: {
    providerId: "openai-compatible",
    modelId: LLM_MODEL,
    url: LLM_URL,
    apiKey: LLM_API_KEY,
    api: "chat",
  },
  tools: { [TOOL]: sendLetter },
  hooks: prudenceHooks({
    aiSystemId: AI_SYSTEM_ID,
    ...(PIPELINES && { pipelines: PIPELINES }),
    ...(env.ERNEST_URL && { evidence: new ErnestEvidence() }),
  }),
});

const result = await agent.generate(TASK, { maxSteps: 4 });
finish(result.text);
