"""prudence-semantic-kernel — Prudence governance for Semantic Kernel (Python).

A function invocation filter that asks Prudence before every kernel function call, including
the ones the model makes through automatic function calling:

    kernel.add_filter(FilterTypes.FUNCTION_INVOCATION,
                      PrudenceFilter(ai_system_id="68bf42c1-…"))

Only ALLOW runs the function. Anything else (DENY, APPROVAL_REQUIRED, Prudence unreachable)
skips it and gives the model the refusal as the function's result. Allowed calls are reported
to Prudence (`POST /api/executions`).
"""
from prudence_governance import Decision, M2MAuth, PrudenceGate

from .filter import PrudenceFilter

__all__ = ["PrudenceFilter", "PrudenceGate", "M2MAuth", "Decision"]
