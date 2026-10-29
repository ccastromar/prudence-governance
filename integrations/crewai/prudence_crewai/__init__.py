"""prudence-crewai — Prudence governance for CrewAI.

Tool call hooks that ask Prudence before every tool call of every crew in the process:

    PrudenceToolHooks(ai_system_id="68bf42c1-…").register()

Only ALLOW runs the tool. Anything else (DENY, APPROVAL_REQUIRED, Prudence unreachable)
blocks it and gives the agent the refusal as the tool's result. Allowed calls are reported to
Prudence (`POST /api/executions`).
"""
from prudence_governance import Decision, M2MAuth, PrudenceGate

from .hooks import PrudenceToolHooks

__all__ = ["PrudenceToolHooks", "PrudenceGate", "M2MAuth", "Decision"]
