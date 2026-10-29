# Conformance: do the adapters really enforce Prudence?

Real agents of thirteen frameworks, each with one tool, each governed by its Prudence adapter,
run against a real Prudence and OPA. The model is scripted (`fake_llm.py`): it always calls
the tool, so whether the tool runs depends only on governance. No model, no API key.

| Framework | Agent | Adapter |
|---|---|---|
| Pydantic AI | `agents/pydantic_agent.py` (`OpenAIChatModel`) | `prudence_pydantic.governed` |
| LangGraph | `agents/langgraph_agent.py` (a graph that drafts with the model) | `prudence_langgraph.governed_node` + `gate_route` |
| Google ADK | `agents/adk_agent.py` (a small Chat Completions model class, not LiteLLM) | `prudence_adk.PrudencePlugin` |
| OpenAI Agents SDK | `agents/openai_agent.py` (Chat Completions) | `prudence_openai_agents.prudence_guardrail` |
| Microsoft Agent Framework | `agents/maf_agent.py` | `prudence_agent_framework.PrudenceMiddleware` |
| Semantic Kernel | `agents/sk_agent.py` (`ChatCompletionAgent`) | `prudence_semantic_kernel.PrudenceFilter` |
| Strands Agents | `agents/strands_agent.py` (OpenAI model provider) | `prudence_strands.PrudenceHooks` |
| LangChain | `agents/langchain_agent.py` (`create_agent`, `ChatOpenAI`) | `prudence_langchain.PrudenceMiddleware` |
| Claude Agent SDK | `agents/claude_agent.py` (the SDK's bundled CLI, an in-process MCP tool) | `prudence_claude_agent.PrudenceHook` |
| CrewAI | `agents/crewai_agent.py` (a one-agent crew) | `prudence_crewai.PrudenceToolHooks` |
| Agno | `agents/agno_agent.py` (`OpenAILike`) | `prudence_agno.PrudenceToolHook` |
| LlamaIndex | `agents/llamaindex_agent.py` (`FunctionAgent`, `OpenAILike`) | `prudence_llamaindex.govern_tools` |
| smolagents | `agents/smolagents_agent.py` (`ToolCallingAgent`) | `prudence_smolagents.govern_tools` |

The Claude Agent SDK agent points its CLI at the scripted model's Anthropic Messages endpoint
(`ANTHROPIC_BASE_URL`), with the CLI's built-in tools off, no user or project settings and a
throw-away config directory, so nothing of the machine's own Claude setup is read or used.

Four cases per framework:

| Case | Setup | Expected |
|---|---|---|
| ALLOW | approved system, granted pipeline | the tool runs; the execution reaches `GET /api/executions` |
| APPROVAL_REQUIRED | high-risk system awaiting approval | the tool does not run; the model is told |
| DENY | a pipeline the runtime was not granted | the tool does not run; the model is told |
| Prudence down | the gate points at a closed port | the tool does not run (fail closed) |

## Run it

One environment per framework, as each company would have (their dependencies conflict: CrewAI
pins `openai` 2.x, the Agents SDK needs 3.x). Python 3.13: CrewAI does not support 3.14 yet. The
same agents are the console's demos ([`../demo-runner`](../demo-runner)); they read `LLM_MODEL` and
`LLM_API_KEY` too, so the runner can point them at a real endpoint.

```bash
cd integrations/conformance
for f in pydantic langgraph adk openai maf sk strands langchain claude crewai agno llamaindex smolagents; do
  python3.13 -m venv .venv-$f && .venv-$f/bin/pip install -r requirements-$f.txt
done
```

Then, in two terminals:

```bash
./stack.sh     # OPA :18181 with opa-data.json, Prudence :18099 (dev, throw-away H2)
./run.py       # registers two AI systems, runs the 52 cases, prints the table
```

`stack.sh` leaves your own dev Prudence (:8099), its database and `policy/opa/data.json`
alone. It runs without Ernest: decisions still come back, and their evidence waits in
Prudence's outbox.

What this does not cover: real tokens (the dev profile takes the subject from the request;
the adapters send their runtime name) and a real model choosing whether to call the tool.
The second run covers the tokens.

## With real login, an MCP gateway and Ernest

`run-sso.py` takes each of the thirteen frameworks through what an adopter does, against Prudence in its secured
configuration (Keycloak JWTs, PostgreSQL, the production start checks), with Ernest in the loop:

1. `scripts/provision-runtime.py` registers one runtime per framework and one MCP gateway
   (Keycloak client, `preferred_username` mapper, OPA grant). OPA starts with **no grant**, so an
   ALLOW can only come from the identity in the token, never from the name an adapter sends.
2. The test user `alice` registers and reviews the AI systems.
3. Each agent runs on its own runtime's client credentials, sealing its executions in Ernest.

| Group | Cases |
|---|---|
| each framework | the token names the runtime; ALLOW runs the tool and is recorded citing an ALLOW decision; APPROVAL_REQUIRED and an ungranted pipeline do not run it; a wrong client secret fails closed |
| Prudence | no token: 401; a person's token: 403 |
| MCP gateway (AuthZEN) | granted tool on an approved system: `true`; system awaiting approval: `false` with the approver; tool not granted: `false`; tool mapped to no AI system: 400 |
| Ernest | one execution per framework sealed under the system; the decisions sealed |
| Prudence × Ernest | no source mismatch for the system both were told about; a run reported without evidence is flagged `REPORTED_NOT_EVIDENCED` |

```bash
../../scripts/keycloak.sh   # the dev realm chestertown on :8081
./stack-sso.sh              # OPA :18181, Prudence secured :18099 (database prudence_conformance),
                            # Ernest :13001 (MongoDB ernest_conformance); both recreated each start
./run-sso.py
```

`ERNEST=off ./stack-sso.sh` runs without Ernest (its checks are skipped). Ernest needs MongoDB and
the sibling `ai-blockchain-provenance` checkout with its backend built.

It uses the dev realm's test credentials (`admin`/`admin`, `alice`/`alice`) and leaves the
`conformance-*-pdp` clients (one per framework, plus the MCP gateway) in the local Keycloak;
delete them there for a clean realm.
