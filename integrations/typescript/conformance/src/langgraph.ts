/** A LangGraph.js agent (createReactAgent) with one tool governed by @chestertown/prudence-langgraph. */
import { tool } from "@langchain/core/tools";
import { createReactAgent } from "@langchain/langgraph/prebuilt";
import { ChatOpenAI } from "@langchain/openai";
import { ErnestEvidence } from "@chestertown/prudence-core";
import { governTools } from "@chestertown/prudence-langgraph";
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

const sendLetter = tool(
  async ({ reference }: { reference: string }) => runTool(reference),
  {
    name: TOOL,
    description: DESCRIPTION,
    schema: z.object({ reference: z.string() }),
  },
);

const tools = governTools([sendLetter], {
  aiSystemId: AI_SYSTEM_ID,
  ...(PIPELINES && { pipelines: PIPELINES }),
  ...(env.ERNEST_URL && { evidence: new ErnestEvidence() }),
});

const llm = new ChatOpenAI({
  model: LLM_MODEL,
  apiKey: LLM_API_KEY,
  configuration: { baseURL: LLM_URL },
});
const graph = createReactAgent({
  llm,
  tools,
  prompt: "Use your tools to do what the user asks.",
});

const state = await graph.invoke(
  { messages: [{ role: "user", content: TASK }] },
  { recursionLimit: 8 },
);
const last = state.messages.at(-1);
finish(
  typeof last?.content === "string"
    ? last.content
    : JSON.stringify(last?.content ?? ""),
);
