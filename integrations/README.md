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

Each demo runs against the live local stack (Prudence :8099 + OPA :8181 + Ernest :3001) with
no LLM key, and shows ALLOW / DENY / APPROVAL_REQUIRED plus the Ernest chain growing as each
governed step is sealed. See each folder's README / example.

**Strategic note.** AOS is one reference enforcement point among these — not the product. The
defensible layer is govern + prove, portable across whatever runtime a team already uses.
