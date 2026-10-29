"""The LangGraph Policy Enforcement Point: wrap a node with the Prudence gate.

`governed_node(fn, ...)` returns a node that consults the Prudence PDP before `fn` runs and,
when allowed, reports the execution to Prudence and seals it in Ernest under the AI system,
as every other adapter does. The decision itself is recorded by Prudence (the PDP seals every
decision it makes), so the node does not record it a second time. `gate_route` is a routing
function for a conditional edge, so the graph itself stops at the gate on DENY /
APPROVAL_REQUIRED — the gate doesn't just annotate state, it gates the flow.

Governance context (subject, registry system id) travels in the graph state, so the same node
runs under different identities and policies unchanged.
"""
from __future__ import annotations

from typing import Callable

from prudence_governance import ErnestEvidence, PrudenceGate


def _tool_io(state: dict) -> dict:
    """Hash only the tool-relevant fields, not the governance plumbing."""
    return {k: v for k, v in state.items()
            if k not in ("subject", "ai_system_id", "model_id", "gate", "reason", "output")}


def governed_node(fn: Callable[[dict], dict], *, pipeline: str, action: str = "run",
                  gate: PrudenceGate, evidence: ErnestEvidence | None = None,
                  subject_key: str = "subject", system_key: str = "ai_system_id") -> Callable[[dict], dict]:
    def node(state: dict) -> dict:
        subject = state[subject_key]
        ai_system = state[system_key]

        decision = gate.decide(subject, pipeline, ai_system, action)

        if not decision.allowed:
            return {"gate": decision.decision, "reason": decision.reason,
                    "output": f"⛔ Governance {decision.decision}: {decision.reason or 'not permitted'}"}

        # Best effort: both calls swallow their own errors. The seal retries while Ernest does
        # not know the system yet (Prudence registers it with its first decision).
        gate.report_execution(decision, ai_system, pipeline, runtime="langgraph")
        if evidence is not None:
            evidence.log_execution(ai_system, pipeline=pipeline, runtime="langgraph",
                                   decision_id=decision.decision_id, inputs={"input": _tool_io(state)})
        updates = fn(state) or {}
        return {"gate": "ALLOW", **updates}

    return node


def gate_route(state: dict) -> str:
    """Conditional-edge router: continue only when the gate allowed."""
    return "allow" if state.get("gate") == "ALLOW" else "stop"
