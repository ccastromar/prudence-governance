# Conformance: do the adapters really enforce Prudence?

Real agents of three frameworks, each with one tool, each governed by its Prudence adapter,
run against a real Prudence and OPA. The model is scripted (`fake_llm.py`): it always calls
the tool, so whether the tool runs depends only on governance. No model, no API key.

| Framework | Agent | Adapter |
|---|---|---|
| Google ADK | `agents/adk_agent.py` (via LiteLLM) | `prudence_adk.PrudencePlugin` |
| OpenAI Agents SDK | `agents/openai_agent.py` (Chat Completions) | `prudence_openai_agents.prudence_guardrail` |
| Microsoft Agent Framework | `agents/maf_agent.py` | `prudence_agent_framework.PrudenceMiddleware` |

Four cases per framework:

| Case | Setup | Expected |
|---|---|---|
| ALLOW | approved system, granted pipeline | the tool runs; the execution reaches `GET /api/executions` |
| APPROVAL_REQUIRED | high-risk system awaiting approval | the tool does not run; the model is told |
| DENY | a pipeline the runtime was not granted | the tool does not run; the model is told |
| Prudence down | the gate points at a closed port | the tool does not run (fail closed) |

## Run it

One environment per framework, as each company would have (their dependencies conflict:
LiteLLM, used by ADK here, pins `openai` 2.x; the Agents SDK needs 3.x):

```bash
cd integrations/conformance
for f in adk openai maf; do python3 -m venv .venv-$f && .venv-$f/bin/pip install -r requirements-$f.txt; done
```

Then, in two terminals:

```bash
./stack.sh     # OPA :18181 with opa-data.json, Prudence :18099 (dev, throw-away H2)
./run.py       # registers two AI systems, runs the 12 cases, prints the table
```

`stack.sh` leaves your own dev Prudence (:8099), its database and `policy/opa/data.json`
alone. It runs without Ernest: decisions still come back, and their evidence waits in
Prudence's outbox.

What this does not cover: real tokens (the dev profile takes the subject from the request;
the adapters send their runtime name) and a real model choosing whether to call the tool.
