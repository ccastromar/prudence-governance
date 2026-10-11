"""prudence-governance — framework-agnostic govern+prove core.

The two pieces every runtime adapter reuses:
  - PrudenceGate: the Policy Enforcement Point client (consults the Prudence PDP).
  - ErnestEvidence: the tamper-evident evidence emitter (seals steps into Ernest).

A per-framework adapter (Pydantic AI, LangGraph, OpenAI Agents SDK, …) is a thin wrapper
that calls these at the right hook point. The policy and the evidence layer never change;
only where the gate is enforced does.
"""
from .evidence import ErnestEvidence, sha256_json
from .gate import Decision, PrudenceGate

__all__ = ["PrudenceGate", "Decision", "ErnestEvidence", "sha256_json"]
