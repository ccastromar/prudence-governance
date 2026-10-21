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
The second run covers the tokens.

## With real login

`run-sso.py` takes the ADK agent through what an adopter does, against Prudence in its secured
configuration (Keycloak JWTs, PostgreSQL, the production start checks):

1. `scripts/provision-runtime.py` registers the runtime `conformance-adk`: Keycloak client,
   `preferred_username` mapper, and the OPA grant. OPA starts with **no grant** for it, so an
   ALLOW can only come from the identity in the token, never from the name the adapter sends.
2. The test user `alice` registers and reviews two AI systems.
3. The agent runs with the runtime's client credentials.

| Case | Expected |
|---|---|
| the runtime's token | `preferred_username = service-account-conformance-adk-pdp`, scopes `pdp.decide execution.report` |
| ALLOW | the tool runs; the execution is recorded and cites an ALLOW decision |
| APPROVAL_REQUIRED, DENY (not granted) | the tool does not run; the model is told |
| wrong client secret | no token, so the gate fails closed and the tool does not run |
| no token / a person's token | Prudence answers 401 / 403 |

```bash
../../scripts/keycloak.sh   # the dev realm chestertown on :8081
./stack-sso.sh              # OPA :18181, Prudence secured :18099, database prudence_conformance (recreated each start)
./run-sso.py                # needs .venv-adk
```

It uses the dev realm's test credentials (`admin`/`admin`, `alice`/`alice`) and leaves the
client `conformance-adk-pdp` in the local Keycloak; delete it there if you want a clean realm.
