# prudence-llamaindex — Prudence governance for LlamaIndex

A **tool wrapper** that asks Prudence before every call of the tools you give the agent.
Only `ALLOW` runs the tool; anything else returns the refusal to the model as an error tool
output. Allowed calls are reported to Prudence.

```python
from llama_index.core.agent.workflow import FunctionAgent
from llama_index.core.tools import FunctionTool
from prudence_llamaindex import govern_tools

tools = govern_tools([FunctionTool.from_defaults(send_customer_letter)],
                     ai_system_id="68bf42c1-…",
                     pipelines={"send_customer_letter": "claims_letter"})
agent = FunctionAgent(tools=tools, llm=llm)
```

- **Wrap every tool:** LlamaIndex has no hook in front of all tool calls, so governance lives on
  the tool. A tool you hand the agent unwrapped is not governed.
- **Same tool for the model:** the wrapper keeps the tool's name, description and schema.
- **Pipeline per tool:** `pipelines[tool name]`, or the tool's name. A tool nobody granted is
  denied.
- **AI system:** one id for the agent, or a mapping per tool name; a tool with no AI system is
  wrapped as always denied.
- **`ungoverned`:** tool names that need no decision (read-only helpers), returned unwrapped.
- **One tool:** `GovernedTool(tool, ai_system_id, pipeline=…)`.
- **With Ernest in the suite:** pass `evidence=ErnestEvidence("https://ernest…")` so each allowed
  execution is also sealed there. Prudence compares the two sources; a run reported to Prudence
  with no evidence in Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`.
- **Credentials:** `PRUDENCE_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET` (from `scripts/provision-runtime.py`), or pass a `PrudenceGate`.

```bash
pip install -e ../prudence-core -e .
```

Tested with llama-index-core 0.14. How the whole integration works:
[docs/integration/README.md](../../docs/integration/README.md).
