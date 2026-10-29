"""prudence-llamaindex — Prudence governance for LlamaIndex.

A tool wrapper that asks Prudence before every call of the tools you give the agent:

    agent = FunctionAgent(tools=govern_tools([send_customer_letter], ai_system_id="68bf42c1-…"),
                          llm=llm)

Only ALLOW runs the tool. Anything else (DENY, APPROVAL_REQUIRED, Prudence unreachable)
returns the refusal as an error tool output. Allowed calls are reported to Prudence
(`POST /api/executions`).
"""
from prudence_governance import Decision, M2MAuth, PrudenceGate

from .tool import GovernedTool, govern_tools

__all__ = ["GovernedTool", "govern_tools", "PrudenceGate", "M2MAuth", "Decision"]
