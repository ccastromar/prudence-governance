"""prudence-governance — framework-agnostic govern+prove core.

The two pieces every runtime adapter reuses:
  - PrudenceGate: the Policy Enforcement Point client (consults the Prudence PDP).
  - ErnestEvidence: the tamper-evident evidence emitter (seals steps into Ernest).

A per-framework adapter (Pydantic AI, LangGraph, OpenAI Agents SDK, …) is a thin wrapper
that calls these at the right hook point. The policy and the evidence layer never change;
only where the gate is enforced does.
"""
from .evidence import ErnestEvidence, sha256_json
from .gate import Decision, M2MAuth, PrudenceGate
from .seal import QtspSealer, signed_bytes

__all__ = ["PrudenceGate", "Decision", "M2MAuth", "ErnestEvidence", "sha256_json", "QtspSealer", "signed_bytes"]
