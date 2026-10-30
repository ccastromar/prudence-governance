# prudence-crewai — Prudence governance for CrewAI

**Tool call hooks** that ask Prudence before every tool call of every crew in the process.
Only `ALLOW` runs the tool; anything else blocks it and gives the agent the refusal as the
tool's result. Allowed calls are reported to Prudence.

```python
from crewai import Agent, Crew, Task
from prudence_crewai import PrudenceToolHooks

hooks = PrudenceToolHooks(ai_system_id="68bf42c1-…",
                          pipelines={"send_customer_letter": "claims_letter"}).register()
crew = Crew(agents=[claims_agent], tasks=[letter_task])
crew.kickoff()
hooks.unregister()   # when the process should stop governing
```

- **Process-wide:** CrewAI keeps one hook list, so every crew and agent in the process is
  governed once `register()` runs.
- **Pipeline per tool:** `pipelines[tool name]`, or the tool's name. A tool nobody granted is
  denied.
- **AI system:** one id for the crew, or a mapping per tool name.
- **`ungoverned`:** tool names that need no decision (read-only helpers).
- **Fails closed:** CrewAI runs the tool when a hook raises, so the hook never does; its own
  errors become a refusal.
- **Sync:** CrewAI hooks are synchronous, so the PDP call and the report run inline.
- **With Ernest:** pass `evidence=ErnestEvidence("https://ernest…")` so each allowed
  execution is also sealed there. Prudence compares the two sources; a run reported to Prudence
  with no evidence in Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`.
- **Credentials:** `PRUDENCE_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET` (from `scripts/provision-runtime.py`), or pass a `PrudenceGate`.

```bash
pip install -e ../prudence-core -e .
```

Tested with crewai 1.15 (Python 3.13; CrewAI does not support 3.14 yet). How the whole
integration works: [docs/integration/README.md](../../docs/integration/README.md).
