# prudence-agno — Prudence governance for Agno

A **tool hook** that asks Prudence before every tool call of the agent. Only `ALLOW` runs the
tool; anything else skips it and gives the model the refusal as the tool's result. Allowed
calls are reported to Prudence.

```python
from agno.agent import Agent
from prudence_agno import PrudenceToolHook

agent = Agent(
    model=model,
    tools=[send_customer_letter],
    tool_hooks=[PrudenceToolHook(ai_system_id="68bf42c1-…",
                                 pipelines={"send_customer_letter": "claims_letter"})],
)
```

- **Pipeline per tool:** `tool:<tool name>` with the action `tools/call`, the grant AI and MCP
  gateways use too (`scripts/provision-runtime.py --tool <name>`); or `pipelines[tool name]`,
  asked with `run`. A tool nobody granted is denied.
- **AI system:** one id for the agent, or a mapping per tool name.
- **`ungoverned`:** tool names that need no decision (read-only helpers).
- **Order:** Agno runs `tool_hooks` in list order, outermost first. Put this hook after any hook
  that rewrites arguments, so it decides on the arguments the tool will get.
- **Sync hook:** Agno skips async hooks in `agent.run`, so this one is synchronous and works in
  `run` and `arun` alike; in `arun` the PDP call holds the event loop for its duration.
- **Teams:** pass it as `tool_hooks` on each member agent (or on the team).
- **With Ernest:** pass `evidence=ErnestEvidence("https://ernest…")` so each allowed
  execution is also sealed there. Prudence compares the two sources; a run reported to Prudence
  with no evidence in Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`.
- **Credentials:** `PRUDENCE_PDP_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET` (from `scripts/provision-runtime.py`), or pass a `PrudenceGate`.
- **One flow per run:** wrap each agent run in `with prudence_governance.run():` and all its
  decisions share one `correlationId` in Prudence and Ernest.
- **Prove it:** [`../conformance-kit`](../conformance-kit) checks the adapter against the
  [adapter contract](../../contract/adapter/README.md) with no Prudence and no model.

```bash
# from a checkout, until the packages are published
pip install -e ../prudence-core -e .
```

Tested with agno 3.1. How the whole integration works:
[docs/integration/README.md](../../docs/integration/README.md).
