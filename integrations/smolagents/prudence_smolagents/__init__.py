"""prudence-smolagents — Prudence governance for smolagents.

A tool wrapper that asks Prudence before every call of the tools you give the agent:

    agent = ToolCallingAgent(tools=govern_tools([send_customer_letter], ai_system_id="68bf42c1-…"),
                             model=model)

Only ALLOW runs the tool. Anything else (DENY, APPROVAL_REQUIRED, Prudence unreachable)
returns the refusal as the tool's result. Allowed calls are reported to Prudence
(`POST /api/executions`).
"""
from prudence_governance import Decision, M2MAuth, PrudenceGate

from .tool import GovernedTool, govern_tools

__all__ = ["GovernedTool", "govern_tools", "PrudenceGate", "M2MAuth", "Decision"]
