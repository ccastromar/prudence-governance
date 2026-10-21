"""prudence-agent-framework — Prudence governance for Microsoft Agent Framework.

A function middleware that asks Prudence before every tool call of the agent:

    agent = Agent(client=client, tools=[send_customer_letter],
                  middleware=[PrudenceMiddleware(ai_system_id="68bf42c1-…")])

Only ALLOW runs the tool. Anything else (DENY, APPROVAL_REQUIRED, Prudence unreachable) skips
it and gives the model the refusal as the tool's result; `stop_on_deny=True` ends the run
instead. Allowed calls are reported to Prudence (`POST /api/executions`).
"""
from prudence_governance import Decision, M2MAuth, PrudenceGate

from .middleware import PrudenceMiddleware

__all__ = ["PrudenceMiddleware", "PrudenceGate", "M2MAuth", "Decision"]
