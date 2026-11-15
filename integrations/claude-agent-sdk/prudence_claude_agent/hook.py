"""The Policy Enforcement Point as a Claude Agent SDK PreToolUse hook.

The SDK runs `PreToolUse` hooks before each tool call (built-in tools such as Bash or Write,
and MCP tools named `mcp__<server>__<tool>`). A hook that answers
`permissionDecision: "deny"` stops the call and the reason goes back to Claude. That is the
gate: anything but ALLOW denies; ALLOW answers nothing, so the SDK's own permission mode still
applies. Governance can only take permission away, never grant what the host would refuse.

Which pipeline a tool is governed as: `pipelines[tool name]` (action `run`) when given,
otherwise `tool:<tool name>` (action `tools/call`), the same grant an AI or MCP gateway uses.
An MCP tool is named without its `mcp__<server>__` prefix, as the gateway names it, so
`mcp__claims__send_customer_letter` is asked as `tool:send_customer_letter`. Mappings
(`pipelines`, `ungoverned`, per-tool `ai_system_id`) take either name; the full one wins, so two
servers with a tool of the same name can still be told apart.
Grant the runtime those pipelines in Prudence (`scripts/provision-runtime.py`); a tool nobody
granted is denied, built-in tools included, unless listed in `ungoverned` or left out by the
matcher pattern.
"""
from __future__ import annotations

import asyncio
import uuid
from typing import Any, Mapping

from claude_agent_sdk import HookContext, HookMatcher

from prudence_governance import ErnestEvidence, PrudenceGate, tool_target


def short_name(tool: str) -> str:
    """`mcp__<server>__<tool>` → `<tool>`; any other name unchanged."""
    if tool.startswith("mcp__"):
        _server, sep, name = tool[len("mcp__"):].partition("__")
        if sep and name:
            return name
    return tool


def _lookup(mapping: Mapping[str, str], tool: str) -> str | None:
    return mapping.get(tool) or mapping.get(short_name(tool))


class PrudenceHook:
    def __init__(
        self,
        ai_system_id: str | Mapping[str, str],
        *,
        gate: PrudenceGate | None = None,
        pipelines: Mapping[str, str] | None = None,
        ungoverned: set[str] | frozenset[str] = frozenset(),
        evidence: ErnestEvidence | None = None,
        runtime: str = "claude-agent-sdk",
    ):
        """`ai_system_id`: the registry id the agent acts as, or one per tool name.
        `ungoverned`: tool names that need no decision (read-only tools such as Read or Grep).
        Mappings take an MCP tool's full name (`mcp__claims__send_customer_letter`) or its own
        (`send_customer_letter`); the full name wins.
        `evidence`: with Ernest in the suite, also seal each allowed execution there."""
        self.gate = gate or PrudenceGate()
        self.ai_system_id = ai_system_id
        self.pipelines = dict(pipelines or {})
        self.ungoverned = frozenset(ungoverned)
        self.evidence = evidence
        self.runtime = runtime

    def matcher(self, pattern: str | None = None) -> HookMatcher:
        """A PreToolUse matcher for `ClaudeAgentOptions(hooks={"PreToolUse": [...]})`.
        `pattern` limits it to some tools ("mcp__claims__.*"); None governs every tool."""
        return HookMatcher(matcher=pattern, hooks=[self])

    async def __call__(self, input_data: dict[str, Any], tool_use_id: str | None,
                       context: HookContext) -> dict[str, Any]:
        if input_data.get("hook_event_name") != "PreToolUse":
            return {}
        tool = input_data["tool_name"]
        name = short_name(tool)
        if tool in self.ungoverned or name in self.ungoverned:
            return {}
        mapped = _lookup(self.pipelines, tool)
        pipeline, action = tool_target(name, {name: mapped} if mapped else None)
        system = self.ai_system_id if isinstance(self.ai_system_id, str) else _lookup(self.ai_system_id, tool)
        if not system:
            # No AI system for this tool: there is nothing Prudence could have approved.
            return self._deny(f"Not permitted by governance: tool {tool!r} has no AI system.")

        # The gate is synchronous (httpx); keep the event loop free while it waits.
        decision = await asyncio.to_thread(self.gate.decide, self.runtime, pipeline, system, action)
        if not decision.allowed:
            return self._deny(decision.refusal())

        # Best effort, never blocks or fails the tool.
        # One id for the report and the evidence, so Prudence can match them.
        execution_id = tool_use_id or input_data.get("tool_use_id") or str(uuid.uuid4())
        loop = asyncio.get_running_loop()
        loop.run_in_executor(None, lambda: self.gate.report_execution(
            decision, system, pipeline, runtime=self.runtime, execution_id=execution_id))
        if self.evidence is not None:
            inputs = dict(input_data.get("tool_input") or {})
            loop.run_in_executor(None, lambda: self.evidence.log_execution(
                system, pipeline=pipeline, runtime=self.runtime, decision_id=decision.decision_id,
                inputs=inputs, execution_id=execution_id))
        return {}

    @staticmethod
    def _deny(message: str) -> dict[str, Any]:
        return {"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                       "permissionDecision": "deny",
                                       "permissionDecisionReason": message}}
