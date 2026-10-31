# demo-runner — the governed demos behind the console

Internal-only runners for Prudence's **Integrations → Governed runs** panel. A demo is a
framework's conformance agent ([`../conformance/agents`](../conformance/agents)): an agent with
one tool, governed by that framework's Prudence adapter. Each run starts the agent in a fresh
process.

## Protocol v1

Prudence is the only caller. Every call except `/healthz` carries the runner's bridge token
(`X-Prudence-Demo-Runner-Token`).

| Call | Answer |
|---|---|
| `GET /demos` | `{protocol, demos: [{id, framework, title, detail, pipeline}]}` — what this runner offers |
| `POST /demos/{id}/runs` `{aiSystemId}` (header `X-Prudence-Demo-Initiator`) | `202 {runId, status: running, …}` |
| `GET /runs/{runId}` | `{status: running\|done\|failed, decision, continued, output, model, error}` |
| `GET /runs` | the latest run of each demo |
| `GET /healthz` | `{status: ok}` |

A runner offers the demos whose adapter its image installs ([`catalog.py`](catalog.py)), or only
those in `DEMOS` (comma list). Prudence aggregates every configured runner (`GET /api/demos`),
starts a run on the runner that declared the demo, and adds what it recorded itself: its PDP
decision for the run and the execution the runtime reported.

## Two images

The frameworks do not fit one environment, so there are two images by dependency family. One
Dockerfile, built from the repository root:

```bash
docker build -f integrations/demo-runner/Dockerfile --build-arg IMAGE=a -t prudence-demo-runner-a .
docker build -f integrations/demo-runner/Dockerfile --build-arg IMAGE=b -t prudence-demo-runner-b .
```

| Image | Frameworks | Why |
|---|---|---|
| [`a`](images/a/requirements.txt) | Pydantic AI, LangGraph, Google ADK, OpenAI Agents SDK, MS Agent Framework, LangChain, Claude Agent SDK, Agno, smolagents | `openai` 3.x, current `mcp` and `pydantic` |
| [`b`](images/b/requirements.txt) | CrewAI, Strands Agents, LlamaIndex, Semantic Kernel | CrewAI pins `openai<3`, `mcp<2`, `pydantic<2.13`; Strands' OpenAI provider and LlamaIndex's OpenAI LLMs pin `openai<3`; Semantic Kernel fits either and sits here so A keeps the current `mcp` and `pydantic` |

Moving a framework to the other image is a requirements change. Releases move these limits, so
check that both images still resolve against the live index:

```bash
python3 check-images.py
```

## Configuration

| Variable | Meaning |
|---|---|
| `PORT` | default 9100 |
| `PRUDENCE_DEMO_RUNNER_TOKEN` | the bridge token Prudence presents (required) |
| `PRUDENCE_URL`, `PRUDENCE_PDP_TOKEN_URL` | the PDP and the token endpoint the agents use |
| `PRUDENCE_PDP_CLIENTS_FILE` | JSON `{demoId: {clientId, clientSecret}}`: each framework's own PDP client, so decisions name the framework's runtime |
| `PRUDENCE_PDP_CLIENT_ID`, `PRUDENCE_PDP_CLIENT_SECRET` | the runner's own client, for a demo with no entry in the file |
| `PRUDENCE_DEMO_PIPELINE` | the pipeline every demo is governed as (default `demo_customer_letter`); grant it to each client |
| `ERNEST_URL`, `ERNEST_API_KEY` | with Ernest, each allowed execution is sealed there |
| `PRUDENCE_DEMO_LLM_BASE_URL` (`_MODEL`, `_API_KEY`) | an OpenAI-compatible `/v1` endpoint, such as AOS's deterministic mock. Without one, the scripted model (`../conformance/fake_llm.py`) runs in-process; it is not a language model and the panel says so. The Claude Agent SDK demo always uses the scripted model's Anthropic endpoint |
| `DEMOS` | limit the demos offered (comma list of ids) |
| `PRUDENCE_DEMO_CONCURRENCY`, `PRUDENCE_DEMO_RUN_TIMEOUT` | runs at once (2) and seconds per run (180) |

Prudence's side: `PRUDENCE_DEMO_RUNNER_URL` / `_TOKEN` (runner A) and
`PRUDENCE_DEMO_RUNNER_B_URL` / `_B_TOKEN` (runner B), or the list
`prudence.demo-runner.runners` in configuration.

## Run it locally

From this folder, in an environment with an image's requirements and adapters installed (the
adapter paths are relative to the image's folder):

```bash
(cd images/a && pip install -r requirements.txt -r adapters.pip)
PRUDENCE_DEMO_RUNNER_TOKEN=local-a PRUDENCE_URL=http://127.0.0.1:8099 PORT=9101 python runner.py
```

and start Prudence with `PRUDENCE_DEMO_RUNNER_URL=http://127.0.0.1:9101`
`PRUDENCE_DEMO_RUNNER_TOKEN=local-a` (and the B pair for a second runner).
