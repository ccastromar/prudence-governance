"""prudence-pydantic — govern + prove for Pydantic AI agents.

A thin Policy Enforcement Point (Prudence PDP) and evidence emitter (Ernest) that drop
into a Pydantic AI agent as a tool decorator. The governance layer is portable: the same
control plane that governs AOS governs any runtime.
"""
from .evidence import ErnestEvidence, sha256_json
from .gate import Decision, PrudenceGate
from .governed import GovDeps, GovernanceDenied, governed

__all__ = [
    "PrudenceGate", "Decision",
    "ErnestEvidence", "sha256_json",
    "governed", "GovDeps", "GovernanceDenied",
]
