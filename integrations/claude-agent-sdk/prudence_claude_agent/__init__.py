"""prudence-claude-agent — Prudence governance for the Claude Agent SDK.

A PreToolUse hook that asks Prudence before every tool call of the agent:

    prudence = PrudenceHook(ai_system_id="68bf42c1-…", ungoverned={"Read", "Grep", "Glob"})
    options = ClaudeAgentOptions(hooks={"PreToolUse": [prudence.matcher()]})

Only ALLOW lets the call through (and the SDK's own permission mode still applies). Anything
else (DENY, APPROVAL_REQUIRED, Prudence unreachable) denies it and Claude gets the refusal.
Allowed calls are reported to Prudence (`POST /api/executions`).
"""
from prudence_governance import Decision, M2MAuth, PrudenceGate

from .hook import PrudenceHook

__all__ = ["PrudenceHook", "PrudenceGate", "M2MAuth", "Decision"]
