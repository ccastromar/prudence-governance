# Prudence integrations — govern + prove, in any runtime

The control loop (`decide → enforce → prove → oversee`) is not tied to AOS. The enforcement
point belongs *where the agents run*; the policy stays outside, in Prudence. These packages
prove it: one framework-agnostic core, a thin adapter per runtime.

```
prudence-core/        prudence_governance — the shared core
                        PrudenceGate   -> POST /api/policy/decisions   (the PEP client)
                        ErnestEvidence -> seals steps into the hashchain (the prove leg)

pydantic-ai/          prudence_pydantic — @governed decorator for a Pydantic AI tool
langgraph/            prudence_langgraph — governed_node + gate_route for a LangGraph graph
google-adk/           prudence_adk — a runner plugin that gates every tool call (Google ADK)
openai-agents/        prudence_openai_agents — a tool input guardrail (OpenAI Agents SDK)
agent-framework/      prudence_agent_framework — function middleware (Microsoft Agent Framework)
conformance/          real agents of ADK, the Agents SDK and Agent Framework, run against a real
                        Prudence: do the adapters enforce ALLOW / APPROVAL_REQUIRED / DENY / down?
demo-runner/          internal HTTP runner used by Prudence's visible framework demos
```

Start with [`docs/integration/README.md`](../docs/integration/README.md): it covers registering a
runtime (`scripts/provision-runtime.py`), the rules every adapter follows, and the HTTP calls
underneath.

Every adapter does the same two things at its framework's natural hook point: consult the
PDP before a step runs (allow / deny / approval-required), and seal the execution in Ernest
afterwards. The decision is sealed by Prudence itself (the PDP records every decision it
makes), so adapters never record it twice: per governed call that is one PDP call and one
Ernest write from the agent. Adding a runtime (CrewAI, …) is a new
thin adapter, not a new core.

## Console demonstrations

The optional `demo-runner` executes the deterministic examples exposed in Prudence's **Policy
decisions → Governed runs** panel, one per framework adapter: a Pydantic AI bug-triage tool, a
LangGraph response-drafting node, a Google ADK claims-letter tool, an OpenAI Agents SDK refund
tool and a Microsoft Agent Framework CRM tool (`agent_demos.py`). The last three seal each
allowed execution in Ernest under the AI system's id. It is internal-only: the browser calls Prudence with an `author`
session, Prudence relays to the runner using a private token, and the runner uses its own
least-privilege M2M identity (`pdp.decide`) to call the PDP. The browser never receives a PDP
or Ernest credential. The deployment creates the runner identity and the bridge token.

By default the runner needs no model: Pydantic AI uses its offline `TestModel`, the LangGraph
node returns a fixed draft, and the three agent frameworks talk to the conformance run's scripted
endpoint (`fake_llm.py`), started in-process. Set `PRUDENCE_DEMO_LLM_BASE_URL` to an OpenAI-compatible `/v1`
endpoint (optionally `PRUDENCE_DEMO_LLM_MODEL`, default `aos-deterministic-mock`, and
`PRUDENCE_DEMO_LLM_API_KEY`) and both frameworks make a real model call over HTTP instead: the
Pydantic AI agent lets the model choose the governed tool, and the LangGraph node asks it for
the draft. AOS's deterministic mock LLM (`aos-mock-llm`) is such an endpoint — it is not a
language model, and each run reports which model answered (`model`), shown in the panel. When
the PDP denies, the LangGraph node does not call the model at all.

`pydantic-ai` and `langgraph` are installable packages (`pip install -e ./pydantic-ai`), each
next to `prudence-core`.

## Qualified seals (eIDAS B2) — optional

Pass a `QtspSealer` to `ErnestEvidence` (the examples do so when `QTSP_CSC_URL` is set) and
every submission is additionally signed with a **qualified electronic seal** over the exact
bytes Ernest canonicalizes (cleanObject → JCS → DSSE PAE), via a QTSP remote-signing
endpoint (CSC). Each agent step's block then carries a `qeseal-ecdsa-p256` envelope with its
X.509 chain; with Ernest at `SEAL_LEVEL=qualified` + `SIGNED_SUBMISSIONS=required`, unsigned
writes are rejected and sealed ones are admitted only if the chain validates to the trusted
CA (the EU LOTL in production). The private key never touches the agent process. Combined
with B1 (a qualified RFC 3161 timestamp on the anchored Merkle root), the evidence is
**govern + *qualified* prove** — admissible, not merely tamper-evident.

```bash
# with the dev QTSP emulator on :4600 and Ernest started with B1+B2 env
export QTSP_CSC_URL=http://127.0.0.1:4600/csc/sign
python example_graph.py      # or example_agent.py
```

Each demo runs against the live local stack (Prudence :8099 + OPA :8181 + Ernest :3001) with
no LLM key, and shows ALLOW / DENY / APPROVAL_REQUIRED plus the Ernest chain growing as each
governed step is sealed. See each folder's README / example.

**Strategic note.** AOS is one reference enforcement point among these — not the product. The
defensible layer is govern + prove, portable across whatever runtime a team already uses.

## High-volume evidence (Ernest event-ingestor)

Set `ERNEST_INGESTOR_URL` (and `ERNEST_INGESTOR_KEY`) and `ErnestEvidence.log_step` queues step
records on Ernest's event-ingestor instead of appending them directly: requests return 202 once
queued, and the inference id doubles as the ingestor's dedupe key. Ingestor records carry no
qualified seal, so a `QtspSealer` with an ingestor URL is rejected.

Note: `log_step` is still synchronous and does not retry; a failed write returns
`(None, {"error": …})` and is not re-sent.
