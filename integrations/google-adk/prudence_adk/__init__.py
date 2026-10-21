"""prudence-adk — Prudence governance for Google's Agent Development Kit (ADK).

A runner plugin that asks Prudence before every tool call of every agent the runner drives:

    runner = InMemoryRunner(agent=root_agent, plugins=[
        PrudencePlugin(ai_system_id="68bf42c1-…"),
    ])

Only ALLOW runs the tool. Anything else (DENY, APPROVAL_REQUIRED, Prudence unreachable)
returns the refusal to the model instead of the tool's result. Allowed calls are reported to
Prudence (`POST /api/executions`) so drift sees them, with or without Ernest.
"""
from prudence_governance import Decision, M2MAuth, PrudenceGate

from .plugin import PrudencePlugin

__all__ = ["PrudencePlugin", "PrudenceGate", "M2MAuth", "Decision"]
