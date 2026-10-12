# prudence-langgraph — govern + prove for LangGraph

A thin adapter over the framework-agnostic [`prudence_governance`](../prudence-core) core.
`governed_node` wraps a LangGraph node as a Policy Enforcement Point, and `gate_route` is a
conditional-edge router so the graph stops **at** the gate on DENY / APPROVAL_REQUIRED — the
gate gates the flow, it doesn't merely annotate state.

```python
b.add_node("score", governed_node(score_node, pipeline="credit-risk-scoring",
                                  action="run", gate=gate, evidence=evidence))
b.add_conditional_edges("score", gate_route, {"allow": "disburse", "stop": END})
```

Before a governed node runs, the Prudence PDP is consulted; after it runs, the step is sealed
in Ernest. Governance context (subject, registry system id, Ernest model id) travels in the
graph state, so the same node runs under different identities and policies unchanged.

## Run the demo

Needs the local stack up (Prudence :8099 + OPA :8181 + Ernest :3001).

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt            # installs the shared core (editable) + langgraph
export ERNEST_API_KEY=<ernest write key>
python example_graph.py
```

Shows ALLOW (authorized → disburses) / DENY (no OPA role → stops at gate) / APPROVAL_REQUIRED
(unapproved system → stops at gate), with the Ernest chain growing per sealed step. Uses
plain nodes, so **no LLM key is required**.

Add qualified seals (eIDAS B2): `export QTSP_CSC_URL=http://127.0.0.1:4600/csc/sign` (dev
QTSP emulator) before running — each step's block then carries a `qeseal-ecdsa-p256` seal.
