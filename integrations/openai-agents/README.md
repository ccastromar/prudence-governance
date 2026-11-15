# prudence-openai-agents — Prudence governance for the OpenAI Agents SDK

A **tool input guardrail** that asks Prudence before the tool runs. Only `ALLOW` runs it;
anything else rejects the call and gives the model the refusal, and the run continues
(`on_deny="raise"` stops the run instead). Allowed calls are reported to Prudence.

```python
from agents import Agent, function_tool
from prudence_openai_agents import govern, prudence_guardrail

guard = prudence_guardrail(ai_system_id="68bf42c1-…",
                           pipelines={"send_customer_letter": "claims_letter"})

@function_tool(tool_input_guardrails=[guard])
def send_customer_letter(customer_id: str) -> str:
    ...

agent = Agent(name="claims", tools=[send_customer_letter, govern(other_tool, guard)])
```

- **Pipeline per tool:** `tool:<tool name>` with the action `tools/call`, the grant AI and MCP
  gateways use too (`scripts/provision-runtime.py --tool <name>`); or `pipelines[tool name]`,
  asked with `run`.
- **AI system:** one id for the agent, or a mapping per tool name.
- **`govern(tool, guard)`** puts the guardrail in front of a tool you did not define.
- **With Ernest:** pass `evidence=ErnestEvidence("https://ernest…")` so each allowed
  execution is also sealed there. Prudence compares the two sources; a run reported to Prudence
  with no evidence in Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`.
- **Credentials:** `PRUDENCE_PDP_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET` (from `scripts/provision-runtime.py`), or pass a `PrudenceGate`.
- **One flow per run:** wrap each agent run in `with prudence_governance.run():` and all its
  decisions share one `correlationId` in Prudence and Ernest.
- **Prove it:** [`../conformance-kit`](../conformance-kit) checks the adapter against the
  [adapter contract](../../contract/adapter/README.md) with no Prudence and no model.

The SDK attaches guardrails per tool: a tool without the guardrail is not governed. Attach it
to every tool that acts.

```bash
# from a checkout, until the packages are published
pip install -e ../prudence-core -e .
```

Tested with openai-agents 0.24. How the whole integration works:
[docs/integration/README.md](../../docs/integration/README.md).
