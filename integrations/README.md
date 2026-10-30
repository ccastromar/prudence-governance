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
langchain/            prudence_langchain — create_agent middleware (LangChain agents)
google-adk/           prudence_adk — a runner plugin that gates every tool call (Google ADK)
openai-agents/        prudence_openai_agents — a tool input guardrail (OpenAI Agents SDK)
agent-framework/      prudence_agent_framework — function middleware (Microsoft Agent Framework)
semantic-kernel/      prudence_semantic_kernel — a function invocation filter (Semantic Kernel)
strands-agents/       prudence_strands — a hook provider that gates every tool call (Strands Agents)
crewai/               prudence_crewai — tool call hooks for every crew in the process (CrewAI)
agno/                 prudence_agno — a tool hook (Agno)
llamaindex/           prudence_llamaindex — a governed tool wrapper (LlamaIndex)
smolagents/           prudence_smolagents — a governed tool wrapper (smolagents)
claude-agent-sdk/     prudence_claude_agent — a PreToolUse hook (Claude Agent SDK)
conformance/          real agents of every framework above, run against a real Prudence: do the
                        adapters enforce ALLOW / APPROVAL_REQUIRED / DENY / down?
demo-runner/          internal HTTP runner used by Prudence's visible framework demos
```

Start with [`docs/integration/README.md`](../docs/integration/README.md): it covers registering a
runtime (`scripts/provision-runtime.py`), the rules every adapter follows, and the HTTP calls
underneath.

Every adapter does the same two things at its framework's natural hook point: consult the
PDP before a step runs (allow / deny / approval-required), and seal the execution in Ernest
afterwards. The decision is sealed by Prudence itself (the PDP records every decision it
makes), so adapters never record it twice: per governed call that is one PDP call and one
Ernest write from the agent. Adding a runtime is a new
thin adapter, not a new core.

## Console demonstrations

Prudence's **Integrations → Governed runs** panel runs a real agent of each framework, one
card per adapter. The agents are the conformance agents above, so what the console shows is what
conformance tests. They run in [`demo-runner`](demo-runner) — internal-only runners that speak
one small protocol (manifest, start a run, read a run) and come in two images, because the
frameworks do not fit one environment: image A holds the `openai` 3.x family, image B the 2.x one
(CrewAI, Strands, LlamaIndex, Semantic Kernel). Prudence lists whatever the configured runners
declare, starts a run on the runner that holds the demo, and shows the run next to what Prudence
itself recorded for it: its PDP decision and the execution the runtime reported.

The browser calls Prudence with an `author` session; Prudence calls a runner with that runner's
private token; the runner's agents call the PDP with their framework's M2M identity. The browser
never receives a PDP or Ernest credential. See [`demo-runner/README.md`](demo-runner/README.md).

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
