# prudence-strands — Prudence governance for Strands Agents

A **hook provider** that asks Prudence before every tool call of the agent. Only `ALLOW` runs
the tool; anything else cancels it and gives the model the refusal as an error tool result.
Allowed calls are reported to Prudence.

```python
from strands import Agent
from prudence_strands import PrudenceHooks

agent = Agent(
    model=model,
    tools=[send_customer_letter],
    hooks=[PrudenceHooks(ai_system_id="68bf42c1-…",
                         pipelines={"send_customer_letter": "claims_letter"})],
)
```

- **Pipeline per tool:** `pipelines[tool name]`, or the tool's name. A tool nobody granted is
  denied.
- **AI system:** one id for the agent, or a mapping per tool name.
- **`ungoverned`:** tool names that need no decision (read-only helpers).
- **Execution id:** the tool use id Strands gives the call, so the report and the model's
  transcript point at the same call.
- **With Ernest:** pass `evidence=ErnestEvidence("https://ernest…")` so each allowed
  execution is also sealed there. Prudence compares the two sources; a run reported to Prudence
  with no evidence in Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`.
- **Credentials:** `PRUDENCE_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET` (from `scripts/provision-runtime.py`), or pass a `PrudenceGate`.

```bash
pip install -e ../prudence-core -e .
```

Tested with strands-agents 1.62. How the whole integration works:
[docs/integration/README.md](../../docs/integration/README.md).
