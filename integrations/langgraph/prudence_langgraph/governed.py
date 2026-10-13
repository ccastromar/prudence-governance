"""The LangGraph Policy Enforcement Point: wrap a node with the Prudence gate.

`governed_node(fn, ...)` returns a node that consults the Prudence PDP before `fn` runs and
seals the execution in Ernest. The decision itself is recorded by Prudence (the PDP seals
every decision it makes), so the node does not record it a second time. `gate_route` is a routing function for
a conditional edge, so the graph itself stops at the gate on DENY / APPROVAL_REQUIRED — the
gate doesn't just annotate state, it gates the flow.

Governance context (subject, registry system id, Ernest model id) travels in the graph
state, so the same node runs under different identities and policies unchanged.
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
                  subject_key: str = "subject", system_key: str = "ai_system_id",
                  model_key: str = "model_id") -> Callable[[dict], dict]:
    def node(state: dict) -> dict:
        subject = state[subject_key]
        ai_system = state[system_key]
        model_id = state.get(model_key)

        decision = gate.decide(subject, pipeline, ai_system, action)

        if not decision.allowed:
            return {"gate": decision.decision, "reason": decision.reason,
                    "output": f"⛔ Governance {decision.decision}: {decision.reason or 'not permitted'}"}

        updates = fn(state) or {}

        if evidence is not None and model_id:
            evidence.log_step(
                model_id,
                inputs={"input": _tool_io(state)},
                output={"updates": updates},
                metadata={"stage": "execution", "pipeline": pipeline, "action": action,
                          "subject": subject, "aiSystemId": ai_system, "decision": "ALLOW"},
            )

        return {"gate": "ALLOW", **updates}

    return node


def gate_route(state: dict) -> str:
    """Conditional-edge router: continue only when the gate allowed."""
    return "allow" if state.get("gate") == "ALLOW" else "stop"
