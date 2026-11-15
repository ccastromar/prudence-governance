# Prudence for TypeScript agent frameworks

The same governance as the Python adapters, for agents written in TypeScript: one core and a
thin adapter per framework, all following the [adapter contract](../../contract/adapter/README.md).
A tool call is asked as `tool:<name>` with `tools/call` (the grant AI and MCP gateways use too),
only an ALLOW runs it, the model reads the refusal otherwise, and the configuration comes from
the same variables (`PRUDENCE_PDP_URL`, `PRUDENCE_PDP_TOKEN_URL`, …).

```
prudence-core/   @chestertown/prudence-core — PrudenceGate, M2MAuth, ErnestEvidence, run(),
                   toolTarget(), and the ToolGovernor every adapter shares
ai-sdk/          @chestertown/prudence-ai-sdk — governTools() for generateText / streamText / ToolLoopAgent
openai-agents/   @chestertown/prudence-openai-agents — a tool input guardrail (OpenAI Agents SDK)
mastra/          @chestertown/prudence-mastra — agent tool hooks: every tool of the agent
langgraph/       @chestertown/prudence-langgraph — governTools() for ToolNode / createReactAgent,
                   governedNode() + gateRoute() for a graph step
conformance/     one agent per framework, each with one governed tool: what the conformance kit runs
```

Node 22 or later (the frameworks need it), ESM only.

## Install

Nothing is published yet. From a checkout:

```bash
cd integrations/typescript
npm install && npm run build
```

and in your project, the core and the adapter you need, from their folders:

```bash
npm install ../prudence-governance/integrations/typescript/prudence-core \
            ../prudence-governance/integrations/typescript/ai-sdk
```

## One flow per run

```ts
import { run } from "@chestertown/prudence-core";

await run(() => agent.generate("Send the letter to customer C-1"));          // a fresh id
await run("ticket-4711", () => agent.generate("…"));                          // your own flow id
```

Every decision inside carries the same `correlationId` (it follows `await`, through an
AsyncLocalStorage), so the run's tool calls read as one flow in Prudence and in Ernest.

## Prove it

Each agent in [`conformance/src`](conformance/src) runs through the
[conformance kit](../conformance-kit), which plays Prudence and the model:

```bash
integrations/conformance-kit/kit.py -- node integrations/typescript/conformance/dist/ai-sdk.js
```

Not yet in the TypeScript core: qualified electronic seals (the Python core's `QtspSealer`).

Format before committing: `npm run format` (Prettier, the same version as Prudence's console;
`npm run format:check` to verify).
