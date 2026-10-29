"""prudence-langchain — Prudence governance for LangChain agents (`create_agent`).

Agent middleware that asks Prudence before every tool call of the agent:

    agent = create_agent(model, tools=[send_customer_letter],
                         middleware=[PrudenceMiddleware(ai_system_id="68bf42c1-…")])

Only ALLOW runs the tool. Anything else (DENY, APPROVAL_REQUIRED, Prudence unreachable)
skips it and gives the model the refusal as an error tool message. Allowed calls are reported
to Prudence (`POST /api/executions`).
"""
from prudence_governance import Decision, M2MAuth, PrudenceGate

from .middleware import PrudenceMiddleware

__all__ = ["PrudenceMiddleware", "PrudenceGate", "M2MAuth", "Decision"]
