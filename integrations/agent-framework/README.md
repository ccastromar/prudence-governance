# prudence-agent-framework — Prudence governance for Microsoft Agent Framework

**Function middleware** that asks Prudence before every tool call of the agent. Only `ALLOW`
runs the tool; anything else skips it and gives the model the refusal as the tool's result
(`stop_on_deny=True` ends the run instead). Allowed calls are reported to Prudence.

```python
from agent_framework import Agent
from prudence_agent_framework import PrudenceMiddleware

agent = Agent(
    client=client,
    tools=[send_customer_letter],
    middleware=[PrudenceMiddleware(ai_system_id="68bf42c1-…",
                                   pipelines={"send_customer_letter": "claims_letter"})],
)
```

- **Pipeline per tool:** `pipelines[tool name]`, or the tool's name. A tool nobody granted is
  denied.
- **AI system:** one id for the agent, or a mapping per tool name.
- **`ungoverned`:** tool names that need no decision (read-only helpers).
- **Order:** register it after any argument-repair middleware; the framework fails closed if
  arguments change after a policy layer saw them.
- **With Ernest in the suite:** pass `evidence=ErnestEvidence("https://ernest…")` so each allowed
  execution is also sealed there. Prudence compares the two sources; a run reported to Prudence
  with no evidence in Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`.
- **Credentials:** `PRUDENCE_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET` (from `scripts/provision-runtime.py`), or pass a `PrudenceGate`.

```bash
pip install -e ../prudence-core -e .
```

Tested with agent-framework-core 1.22. How the whole integration works:
[docs/integration/README.md](../../docs/integration/README.md).
