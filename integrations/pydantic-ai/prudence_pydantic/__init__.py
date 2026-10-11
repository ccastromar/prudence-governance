"""prudence-pydantic — govern + prove for Pydantic AI.

A thin adapter over the framework-agnostic `prudence_governance` core: a `@governed`
decorator that turns any Pydantic AI tool into a Policy Enforcement Point. The gate and the
evidence layer are shared with every other runtime adapter; only this file is Pydantic-AI-specific.
"""
from prudence_governance import Decision, ErnestEvidence, PrudenceGate, sha256_json

from .governed import GovDeps, GovernanceDenied, governed

__all__ = [
    "PrudenceGate", "Decision",
    "ErnestEvidence", "sha256_json",
    "governed", "GovDeps", "GovernanceDenied",
]
