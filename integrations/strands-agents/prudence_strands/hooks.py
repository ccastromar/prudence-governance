"""The Policy Enforcement Point as a Strands Agents hook provider.

Strands fires `BeforeToolCallEvent` just before each tool runs; when a callback sets
`cancel_tool` to a message, the tool is skipped and the message becomes an error tool result.
That is the gate: ALLOW leaves the event alone (the tool runs), anything else cancels it with
the refusal.

Which pipeline a tool is governed as: `pipelines[tool name]` when given, otherwise the tool's
name. Grant the runtime those pipelines in Prudence (`scripts/provision-runtime.py`); a tool
nobody granted is denied, so a new tool cannot slip through ungoverned.
"""
from __future__ import annotations

import asyncio
from typing import Any, Mapping

from strands.hooks import BeforeToolCallEvent, HookProvider, HookRegistry

from prudence_governance import ErnestEvidence, PrudenceGate


class PrudenceHooks(HookProvider):
    def __init__(
        self,
        ai_system_id: str | Mapping[str, str],
        *,
        gate: PrudenceGate | None = None,
        pipelines: Mapping[str, str] | None = None,
        ungoverned: set[str] | frozenset[str] = frozenset(),
        evidence: ErnestEvidence | None = None,
        runtime: str = "strands-agents",
    ):
        """`ai_system_id`: the registry id the agent acts as, or one per tool name.
        `ungoverned`: tool names that need no decision (read-only helpers, for example).
        `evidence`: with Ernest in the suite, also seal each allowed execution there."""
        self.gate = gate or PrudenceGate()
        self.ai_system_id = ai_system_id
        self.pipelines = dict(pipelines or {})
        self.ungoverned = frozenset(ungoverned)
        self.evidence = evidence
        self.runtime = runtime

    def register_hooks(self, registry: HookRegistry, **kwargs: Any) -> None:
        registry.add_callback(BeforeToolCallEvent, self.before_tool_call)

    async def before_tool_call(self, event: BeforeToolCallEvent) -> None:
        tool = event.tool_use["name"]
        if tool in self.ungoverned:
            return
        pipeline = self.pipelines.get(tool, tool)
        system = self.ai_system_id if isinstance(self.ai_system_id, str) else self.ai_system_id.get(tool)
        if not system:
            # No AI system for this tool: there is nothing Prudence could have approved.
            event.cancel_tool = f"Not permitted by governance: tool {tool!r} has no AI system."
            return

        # The gate is synchronous (httpx); keep the event loop free while it waits.
        decision = await asyncio.to_thread(self.gate.decide, self.runtime, pipeline, system, "run")
        if not decision.allowed:
            event.cancel_tool = decision.refusal()
            return

        # Best effort, never blocks or fails the tool.
        execution_id = event.tool_use["toolUseId"]
        loop = asyncio.get_running_loop()
        loop.run_in_executor(None, lambda: self.gate.report_execution(
            decision, system, pipeline, runtime=self.runtime, execution_id=execution_id))
        if self.evidence is not None:
            inputs = event.tool_use.get("input") or {}
            loop.run_in_executor(None, lambda: self.evidence.log_execution(
                system, pipeline=pipeline, runtime=self.runtime, decision_id=decision.decision_id,
                inputs=inputs))
