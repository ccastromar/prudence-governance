# prudence-pydantic — govern + prove for Pydantic AI

A thin **Policy Enforcement Point** (Prudence PDP) and **evidence emitter** (Ernest) that
drop into a [Pydantic AI](https://ai.pydantic.dev) agent as a single tool decorator.

The point: **the governance layer is portable.** The same control plane that governs AOS
(`decide → enforce → prove → oversee`) governs any runtime. AOS is one reference
enforcement point; this is another. The enforcement point belongs *where the agents run*;
the policy stays outside, in Prudence, versioned.

```python
@agent.tool
@governed(pipeline="credit-risk-scoring", action="run")
def score_credit_risk(ctx, applicant_id: str, amount: float) -> str:
    ...
```

Before the tool runs, the Prudence PDP is consulted (`allow | deny | approval-required`).
After it runs, the step is sealed in Ernest's hashchain. The agent framework never sees
the policy. The governed subject, the registry system id and the evidence clients travel in
the agent's dependencies, so one tool can run under different identities and policies
without changing its code.

## What gets sealed

Per governed call, Ernest records:
- a **governance-decision** block (who asked what, under which verdict), and
- an **execution** block (when the call was allowed).

Only SHA-256 hashes of inputs/outputs are sent — raw data never leaves the process.

## Run the demo

Needs the local stack up (Prudence :8099 + OPA :8181 + Ernest :3001) and
`ERNEST_API_KEY` exported for the evidence writes.

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
export ERNEST_API_KEY=<ernest write key>
python example_agent.py
```

The demo registers an approved and a pending system in Prudence, then runs the agent three
times to show **ALLOW** (authorized), **DENY** (OPA grants the caller no role) and
**APPROVAL_REQUIRED** (the system is not yet approved) — and prints the Ernest chain growing
as each governed step is sealed.

Uses Pydantic AI's `TestModel`, so **no LLM API key is required**: the agent still drives
the tool, and the governance layer is the real subject of the demo.

Add qualified seals (eIDAS B2): `export QTSP_CSC_URL=http://127.0.0.1:4600/csc/sign` (dev
QTSP emulator) before running — each step's block then carries a `qeseal-ecdsa-p256` seal.
