# @chestertown/prudence-langgraph — Prudence governance for LangGraph.js

Two places for the gate, as in the Python adapter.

**In front of each tool,** for `ToolNode`, `createReactAgent` or any LangChain tool caller. Only
`ALLOW` runs the tool; anything else returns the refusal as the tool's result, so the model reads
why:

```ts
import { createReactAgent } from "@langchain/langgraph/prebuilt";
import { governTools } from "@chestertown/prudence-langgraph";

const tools = governTools([sendCustomerLetter], { aiSystemId: "68bf42c1-…" });
const graph = createReactAgent({ llm, tools });
```

**In front of a node,** when a graph step rather than a tool is the unit: the node asks about your
pipeline and the conditional edge stops the graph on anything but ALLOW.

```ts
import { gateRoute, governedNode } from "@chestertown/prudence-langgraph";

builder.addNode("send", governedNode(sendLetter, { pipeline: "claims_letter" }))
       .addConditionalEdges("send", gateRoute, { allow: "next", stop: END });
```

The node reads the subject and the AI system from the state (`subject`, `aiSystemId`); a state with
no AI system is refused without asking.

- **Pipeline per tool:** `tool:<tool name>` with the action `tools/call`, the grant AI and MCP
  gateways use too (`scripts/provision-runtime.py --tool <name>`); or `pipelines[tool name]`, asked
  with `run`. A tool nobody granted is denied. Only the tools passed through `governTools` are
  governed.
- **Reported when allowed,** under the tool call id; with `evidence: new ErnestEvidence()` also
  sealed in Ernest. Both in the background, never failing the step.
- **Credentials:** `PRUDENCE_PDP_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET`, or pass `gate: new PrudenceGate({ … })`.
- **One flow per run:** `await run(() => graph.invoke(…))` from `@chestertown/prudence-core`.
- **Prove it:** `integrations/conformance-kit/kit.py -- node integrations/typescript/conformance/dist/langgraph.js`.
