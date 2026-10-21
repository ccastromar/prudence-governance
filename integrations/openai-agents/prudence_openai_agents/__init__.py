"""prudence-openai-agents — Prudence governance for the OpenAI Agents SDK.

A tool input guardrail that asks Prudence before the tool runs:

    guard = prudence_guardrail(ai_system_id="68bf42c1-…")

    @function_tool(tool_input_guardrails=[guard])
    def send_customer_letter(customer_id: str) -> str: ...

    # or, for tools you did not define:
    govern(other_tool, guard)

Only ALLOW runs the tool. Anything else (DENY, APPROVAL_REQUIRED, Prudence unreachable)
rejects the call and gives the model the refusal; `on_deny="raise"` stops the run instead.
Allowed calls are reported to Prudence (`POST /api/executions`).
"""
from prudence_governance import Decision, M2MAuth, PrudenceGate

from .guardrail import govern, prudence_guardrail

__all__ = ["prudence_guardrail", "govern", "PrudenceGate", "M2MAuth", "Decision"]
