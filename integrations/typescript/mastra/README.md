# @chestertown/prudence-mastra — Prudence governance for Mastra

Agent tool hooks: Mastra runs `beforeToolCall` before every tool call of the agent. Only `ALLOW`
runs the tool; anything else skips it and the model reads the refusal as the tool's result.

```ts
import { Agent } from "@mastra/core/agent";
import { prudenceHooks } from "@chestertown/prudence-mastra";

const agent = new Agent({ id: "claims", name: "claims", instructions, model,
                          tools: { send_customer_letter: sendLetter },
                          hooks: prudenceHooks({ aiSystemId: "68bf42c1-…" }) });
```

- **Every tool of the agent,** tools added later included. List read-only ones in `ungoverned`.
- **Per-call hooks replace the agent's:** if you pass `hooks` to `generate` or `stream`, combine
  them with `withPrudence(prudence, yourHooks)` so governance still runs first.
- **Pipeline per tool:** `tool:<tool name>` (the name the model calls) with the action
  `tools/call`, the grant AI and MCP gateways use too (`scripts/provision-runtime.py --tool <name>`);
  or `pipelines[tool name]`, asked with `run`. A tool nobody granted is denied.
- **AI system:** one id for the agent, or one per tool name; a tool with none is refused without
  asking Prudence.
- **Reported when allowed,** under the agent's tool call id; with `evidence: new ErnestEvidence()`
  also sealed in Ernest. Both in the background, never failing the tool.
- **Credentials:** `PRUDENCE_PDP_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET`, or pass `gate: new PrudenceGate({ … })`.
- **One flow per run:** `await run(() => agent.generate(…))` from `@chestertown/prudence-core`.
- **Prove it:** `integrations/conformance-kit/kit.py -- node integrations/typescript/conformance/dist/mastra.js`.
