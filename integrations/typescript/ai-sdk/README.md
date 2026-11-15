# @chestertown/prudence-ai-sdk — Prudence governance for the Vercel AI SDK

The AI SDK has no hook in front of a tool, so the adapter wraps the tools you hand to
`generateText`, `streamText` or a `ToolLoopAgent`. Only `ALLOW` runs a tool; anything else returns
the refusal as the tool's result, so the model reads why and the run goes on.

```ts
import { generateText, tool } from "ai";
import { governTools } from "@chestertown/prudence-ai-sdk";

const tools = governTools(
  { send_customer_letter: tool({ description, inputSchema, execute }) },
  { aiSystemId: "68bf42c1-…" },
);
await generateText({ model, tools, prompt: "Send the letter to customer C-1" });
```

- **Pipeline per tool:** `tool:<tool name>` (the key in the tool set) with the action
  `tools/call`, the grant AI and MCP gateways use too (`scripts/provision-runtime.py --tool <name>`);
  or `pipelines: { [name]: "claims_letter" }`, asked with `run`. A tool nobody granted is denied.
- **Only what you wrap is governed:** pass the whole tool set through `governTools`; list read-only
  tools in `ungoverned`. Tools with no `execute` (client-side, or run by the provider) pass through
  unchanged: nothing runs here to gate, so govern those at an AI gateway.
- **AI system:** one id for the agent, or one per tool name; a tool with none is refused without
  asking Prudence.
- **Reported when allowed,** under the SDK's tool call id; with `evidence: new ErnestEvidence()`
  also sealed in Ernest. Both in the background, never failing the tool.
- **A tool's own `toModelOutput`** is skipped for a refused call: the model gets the refusal as text.
  A streaming tool (`execute` yields) is drained and its last value is the result.
- **Credentials:** `PRUDENCE_PDP_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET`, or pass `gate: new PrudenceGate({ … })`.
- **One flow per run:** `await run(() => generateText(…))` from `@chestertown/prudence-core`.
- **Prove it:** `integrations/conformance-kit/kit.py -- node integrations/typescript/conformance/dist/ai-sdk.js`.
