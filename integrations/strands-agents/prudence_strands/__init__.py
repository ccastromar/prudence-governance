"""prudence-strands — Prudence governance for Strands Agents.

A hook provider that asks Prudence before every tool call of the agent:

    agent = Agent(model=model, tools=[send_customer_letter],
                  hooks=[PrudenceHooks(ai_system_id="68bf42c1-…")])

Only ALLOW runs the tool. Anything else (DENY, APPROVAL_REQUIRED, Prudence unreachable)
cancels it and gives the model the refusal as an error tool result. Allowed calls are reported
to Prudence (`POST /api/executions`).
"""
from prudence_governance import Decision, M2MAuth, PrudenceGate

from .hooks import PrudenceHooks

__all__ = ["PrudenceHooks", "PrudenceGate", "M2MAuth", "Decision"]
