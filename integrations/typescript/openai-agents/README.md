# @chestertown/prudence-openai-agents — Prudence governance for the OpenAI Agents SDK (JS)

A tool input guardrail: the SDK runs it before the tool. Only `ALLOW` runs the tool; anything
else rejects the call and the model reads the refusal (`onDeny: "throw"` stops the run instead).

```ts
import { Agent, run, tool } from "@openai/agents";
import { prudenceGuardrail, govern } from "@chestertown/prudence-openai-agents";

const guard = prudenceGuardrail({ aiSystemId: "68bf42c1-…" });
const sendLetter = tool({ name: "send_customer_letter", description, parameters, execute,
                          inputGuardrails: [guard] });
govern(someToolYouDidNotDefine, guard);
await run(new Agent({ name: "claims", tools: [sendLetter] }), "Send the letter to customer C-1");
```

- **Pipeline per tool:** `tool:<tool name>` with the action `tools/call`, the grant AI and MCP
  gateways use too (`scripts/provision-runtime.py --tool <name>`); or `pipelines[tool name]`, asked
  with `run`. A tool nobody granted is denied.
- **Per tool:** a guardrail sits on the tools you give it to (`inputGuardrails`, or `govern()` for
  tools you did not define). A tool without it is not governed.
- **AI system:** one id for the agent, or one per tool name; a tool with none is refused without
  asking Prudence.
- **Reported when allowed,** under the SDK's call id; with `evidence: new ErnestEvidence()` also
  sealed in Ernest. Both in the background, never failing the tool.
- **Credentials:** `PRUDENCE_PDP_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET`, or pass `gate: new PrudenceGate({ … })`.
- **One flow per run:** `import { run as flow } from "@chestertown/prudence-core"`, then
  `await flow(() => run(agent, input))`.
- **Prove it:** `integrations/conformance-kit/kit.py -- node integrations/typescript/conformance/dist/openai-agents.js`.
