# prudence-langchain — Prudence governance for LangChain agents

**Agent middleware** for `create_agent` that asks Prudence before every tool call. Only
`ALLOW` runs the tool; anything else skips it and gives the model the refusal as an error tool
message. Allowed calls are reported to Prudence.

```python
from langchain.agents import create_agent
from prudence_langchain import PrudenceMiddleware

agent = create_agent(
    model,
    tools=[send_customer_letter],
    middleware=[PrudenceMiddleware(ai_system_id="68bf42c1-…",
                                   pipelines={"send_customer_letter": "claims_letter"})],
)
```

- **LangChain or LangGraph:** this governs every tool of a `create_agent` agent. For a
  hand-built LangGraph graph, use [`../langgraph`](../langgraph) (a governed node and a routing
  gate).
- **Pipeline per tool:** `pipelines[tool name]`, or the tool's name. A tool nobody granted is
  denied.
- **AI system:** one id for the agent, or a mapping per tool name.
- **`ungoverned`:** tool names that need no decision (read-only helpers).
- **Order:** middleware compose first-defined-outermost. Put this one after any middleware that
  rewrites tool arguments.
- **Execution id:** the model's tool call id, so the report and the transcript point at the
  same call.
- **Sync and async:** `invoke` and `ainvoke` are both governed.
- **With Ernest:** pass `evidence=ErnestEvidence("https://ernest…")` so each allowed
  execution is also sealed there. Prudence compares the two sources; a run reported to Prudence
  with no evidence in Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`.
- **Credentials:** `PRUDENCE_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET` (from `scripts/provision-runtime.py`), or pass a `PrudenceGate`.

```bash
pip install -e ../prudence-core -e .
```

Tested with langchain 1.4. How the whole integration works:
[docs/integration/README.md](../../docs/integration/README.md).
