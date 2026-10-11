"""prudence-langgraph — govern + prove for LangGraph.

A thin adapter over the framework-agnostic `prudence_governance` core: wrap any LangGraph
node as a Policy Enforcement Point, and route the graph on the verdict. Same gate, same
evidence layer as the Pydantic AI adapter — only the hook point differs.
"""
from prudence_governance import Decision, ErnestEvidence, PrudenceGate, sha256_json

from .governed import gate_route, governed_node

__all__ = ["governed_node", "gate_route", "PrudenceGate", "Decision", "ErnestEvidence", "sha256_json"]
