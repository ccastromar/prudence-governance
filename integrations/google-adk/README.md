# prudence-adk — Prudence governance for Google ADK

A runner plugin that asks Prudence before **every tool call of every agent** the runner drives.
Only `ALLOW` runs the tool; anything else returns the refusal to the model as the tool's
response. Allowed calls are reported to Prudence so drift sees them.

```python
from google.adk.runners import InMemoryRunner
from prudence_adk import PrudencePlugin

runner = InMemoryRunner(
    agent=root_agent,
    plugins=[PrudencePlugin(ai_system_id="68bf42c1-…",
                            pipelines={"send_customer_letter": "claims_letter"})],
)
```

- **Pipeline per tool:** `pipelines[tool name]`, or the tool's name. A tool nobody granted is
  denied, so a new tool cannot slip through ungoverned.
- **AI system:** one id for the whole agent, or a mapping per tool name.
- **`ungoverned`:** tool names that need no decision (read-only helpers).
- **With Ernest:** pass `evidence=ErnestEvidence("https://ernest…")` so each allowed
  execution is also sealed there. Prudence compares the two sources; a run reported to Prudence
  with no evidence in Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`.
- **Credentials:** `PRUDENCE_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET` (from `scripts/provision-runtime.py`), or pass a `PrudenceGate`.

```bash
pip install -e ../prudence-core -e .
```

Tested with google-adk 2.12. How the whole integration works:
[docs/integration/README.md](../../docs/integration/README.md).
