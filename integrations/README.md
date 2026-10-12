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
```

Every adapter does the same two things at its framework's natural hook point: consult the
PDP before a step runs (allow / deny / approval-required), and seal the decision **and** the
execution in Ernest afterwards. Adding a runtime (OpenAI Agents SDK, CrewAI, …) is a new
thin adapter, not a new core.

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
