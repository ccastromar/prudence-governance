"""End-to-end demo: a LangGraph pipeline whose scoring node is governed by Prudence and
sealed by Ernest — the same control loop as the Pydantic AI demo, a different runtime, the
same shared core. No AOS, no LLM key.

A conditional edge routes the graph on the gate verdict, so a disbursement node downstream
runs only when the gate allowed — the gate gates the flow, it doesn't merely annotate it.

    python example_graph.py
"""
from __future__ import annotations

import hashlib
import os
import uuid
from typing import TypedDict

import httpx
from langgraph.graph import END, START, StateGraph

from prudence_governance import ErnestEvidence, PrudenceGate, QtspSealer
from prudence_langgraph import gate_route, governed_node

PRUDENCE = os.getenv("PRUDENCE_URL", "http://127.0.0.1:8099")


class CreditState(TypedDict, total=False):
    subject: str          # governance context
    ai_system_id: str
    model_id: str
    applicant_id: str     # tool io
    amount: float
    gate: str
    reason: str
    output: str
    disbursed: bool


def register_system(risk_tier: str, approve: bool) -> str:
    r = httpx.post(f"{PRUDENCE}/api/registry/systems", json={
        "name": "Credit risk scoring (LangGraph)",
        "intendedPurpose": "Creditworthiness of natural persons",
        "ownerName": "Alice", "ownerEmail": "alice@bank.eu", "riskTier": risk_tier,
    }, timeout=5)
    sid = r.json()["id"]
    if approve:
        httpx.post(f"{PRUDENCE}/api/registry/systems/{sid}/approve", timeout=5)
    return sid


def score_node(state: CreditState) -> dict:
    """The governed step: score a credit applicant (toy deterministic model)."""
    aid = state.get("applicant_id", "A-1001")
    amt = state.get("amount", 15000.0)
    h = int(hashlib.sha256(f"{aid}:{amt}".encode()).hexdigest(), 16) % 1000
    score = round(300 + (h / 1000) * 550)
    band = "approve" if score >= 640 else "refer"
    return {"output": f"applicant={aid} amount={amt} score={score} decision={band}"}


def disburse_node(state: CreditState) -> dict:
    """Downstream step reached only when the gate allowed."""
    return {"disbursed": True, "output": state["output"] + " | funds disbursed"}


def build_graph(gate: PrudenceGate, evidence: ErnestEvidence):
    b = StateGraph(CreditState)
    b.add_node("score", governed_node(score_node, pipeline="credit-risk-scoring", action="run",
                                      gate=gate, evidence=evidence))
    b.add_node("disburse", disburse_node)
    b.add_edge(START, "score")
    b.add_conditional_edges("score", gate_route, {"allow": "disburse", "stop": END})
    b.add_edge("disburse", END)
    return b.compile()


def run_once(graph, label: str, subject: str, ai_system_id: str, model_id: str) -> None:
    out = graph.invoke({"subject": subject, "ai_system_id": ai_system_id, "model_id": model_id,
                        "applicant_id": "A-1001", "amount": 15000.0})
    print(f"[{label:>8}] subject={subject:<18} gate={out.get('gate',''):<16} "
          f"disbursed={out.get('disbursed', False)!s:<5} -> {out.get('output')}")


def main() -> None:
    gate = PrudenceGate(PRUDENCE)
    csc = os.getenv("QTSP_CSC_URL")  # e.g. http://127.0.0.1:4600/csc/sign
    sealer = QtspSealer(csc) if csc else None
    evidence = ErnestEvidence(sealer=sealer)
    print(f"qualified seal (eIDAS B2): {'ON via ' + csc if sealer else 'off'}")

    approved = register_system("HIGH", approve=True)
    pending = register_system("HIGH", approve=False)
    run = uuid.uuid4().hex[:8]
    model_id = f"credit-risk-scoring-langgraph-{run}"
    ms, _ = evidence.ensure_model(model_id, f"Credit risk scoring graph (LangGraph) {run}", "1.0.0",
                                  metadata={"framework": "langgraph", "aiSystemId": approved})
    print(f"model registration: HTTP {ms}")

    graph = build_graph(gate, evidence)
    before = evidence.verify().get("blocksVerified")
    print(f"approved system: {approved}")
    print(f"pending  system: {pending}")
    print(f"Ernest chain before: {before} blocks\n")

    run_once(graph, "ALLOW", "alice@bank.eu", approved, model_id)      # authorized -> disburses
    run_once(graph, "DENY", "mallory@bank.eu", approved, model_id)     # no OPA role -> stops at gate
    run_once(graph, "APPROVAL", "alice@bank.eu", pending, model_id)    # unapproved system -> stops at gate

    after = evidence.verify()
    print(f"\nErnest chain after:  {after.get('blocksVerified')} blocks  (valid={after.get('isValid')})")
    print("Same shared core as the Pydantic AI adapter — only the hook point changed.")


if __name__ == "__main__":
    main()
