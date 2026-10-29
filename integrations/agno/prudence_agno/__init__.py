"""prudence-agno — Prudence governance for Agno.

A tool hook that asks Prudence before every tool call of the agent:

    agent = Agent(model=model, tools=[send_customer_letter],
                  tool_hooks=[PrudenceToolHook(ai_system_id="68bf42c1-…")])

Only ALLOW runs the tool. Anything else (DENY, APPROVAL_REQUIRED, Prudence unreachable)
skips it and gives the model the refusal as the tool's result. Allowed calls are reported to
Prudence (`POST /api/executions`).
"""
from prudence_governance import Decision, M2MAuth, PrudenceGate

from .hook import PrudenceToolHook

__all__ = ["PrudenceToolHook", "PrudenceGate", "M2MAuth", "Decision"]
