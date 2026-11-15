"""prudence-governance — framework-agnostic govern+prove core.

The two pieces every runtime adapter reuses:
  - PrudenceGate: the Policy Enforcement Point client (consults the Prudence PDP).
  - ErnestEvidence: the tamper-evident evidence emitter (seals steps into Ernest).
  - run() / tool_target(): one flow id per agent run, one name per tool call.

A per-framework adapter (Pydantic AI, LangGraph, OpenAI Agents SDK, …) is a thin wrapper
that calls these at the right hook point. The policy and the evidence layer never change;
only where the gate is enforced does.
"""
from .evidence import ErnestEvidence, sha256_json
from .gate import Decision, M2MAuth, PrudenceGate
from .run import RUN_ACTION, TOOL_ACTION, current_correlation_id, run, tool_target
from .seal import QtspSealer, signed_bytes

__all__ = ["PrudenceGate", "Decision", "M2MAuth", "ErnestEvidence", "sha256_json", "QtspSealer", "signed_bytes",
           "run", "current_correlation_id", "tool_target", "TOOL_ACTION", "RUN_ACTION"]
