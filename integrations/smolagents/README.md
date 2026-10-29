# prudence-smolagents — Prudence governance for smolagents

A **tool wrapper** that asks Prudence before every call of the tools you give the agent.
Only `ALLOW` runs the tool; anything else returns the refusal to the model as the tool's
result. Allowed calls are reported to Prudence.

```python
from smolagents import ToolCallingAgent
from prudence_smolagents import govern_tools

agent = ToolCallingAgent(
    tools=govern_tools([send_customer_letter], ai_system_id="68bf42c1-…",
                       pipelines={"send_customer_letter": "claims_letter"}),
    model=model,
)
```

- **Wrap every tool:** smolagents has no hook in front of all tool calls (step callbacks run
  after the step), so governance lives on the tool. A tool you hand the agent unwrapped is not
  governed.
- **Both agent kinds:** a `ToolCallingAgent` calls the tool by name, a `CodeAgent`'s generated
  code calls it as a function; both go through the wrapper. In a `CodeAgent` the refusal comes
  back as the function's return value.
- **Same tool for the model:** the wrapper keeps the tool's name, description, inputs and output
  type.
- **Pipeline per tool:** `pipelines[tool name]`, or the tool's name. A tool nobody granted is
  denied.
- **AI system:** one id for the agent, or a mapping per tool name; a tool with no AI system is
  wrapped as always denied.
- **`ungoverned`:** tool names that need no decision (read-only helpers), returned unwrapped.
- **With Ernest in the suite:** pass `evidence=ErnestEvidence("https://ernest…")` so each allowed
  execution is also sealed there. Prudence compares the two sources; a run reported to Prudence
  with no evidence in Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`.
- **Credentials:** `PRUDENCE_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET` (from `scripts/provision-runtime.py`), or pass a `PrudenceGate`.

```bash
pip install -e ../prudence-core -e .
```

Tested with smolagents 1.26. How the whole integration works:
[docs/integration/README.md](../../docs/integration/README.md).
