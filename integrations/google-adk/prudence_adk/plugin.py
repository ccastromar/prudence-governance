"""The ADK runner plugin: a Policy Enforcement Point in front of every tool call.

ADK runs a plugin's `before_tool_callback` before each tool; when it returns a dict, the tool
is skipped and the dict becomes the tool's response. That is the gate: ALLOW returns None (the
tool runs), anything else returns the refusal.

Which pipeline a tool is governed as: `pipelines[tool.name]` when given, otherwise the tool's
name. Grant the runtime those pipelines in Prudence (`scripts/provision-runtime.py`); a tool
nobody granted is denied, so a new tool cannot slip through ungoverned.
"""
from __future__ import annotations

import asyncio
from typing import Any, Mapping

from google.adk.plugins.base_plugin import BasePlugin
from google.adk.tools.base_tool import BaseTool
from google.adk.tools.tool_context import ToolContext

from prudence_governance import ErnestEvidence, PrudenceGate


class PrudencePlugin(BasePlugin):
    def __init__(
        self,
        ai_system_id: str | Mapping[str, str],
        *,
        gate: PrudenceGate | None = None,
        pipelines: Mapping[str, str] | None = None,
        ungoverned: set[str] | frozenset[str] = frozenset(),
        evidence: ErnestEvidence | None = None,
        runtime: str = "google-adk",
        name: str = "prudence",
    ):
        """`ai_system_id`: the registry id the agent acts as, or one per tool name.
        `ungoverned`: tool names that need no decision (read-only helpers, for example).
        `evidence`: with Ernest in the suite, also seal each allowed execution there, so Ernest
        and the runtime's report to Prudence tell the same story."""
        super().__init__(name=name)
        self.gate = gate or PrudenceGate()
        self.ai_system_id = ai_system_id
        self.pipelines = dict(pipelines or {})
        self.ungoverned = frozenset(ungoverned)
        self.evidence = evidence
        self.runtime = runtime

    def _system_for(self, tool: str) -> str | None:
        if isinstance(self.ai_system_id, str):
            return self.ai_system_id
        return self.ai_system_id.get(tool)

    async def before_tool_callback(
        self, *, tool: BaseTool, tool_args: dict[str, Any], tool_context: ToolContext
    ) -> dict[str, Any] | None:
        if tool.name in self.ungoverned:
            return None
        pipeline = self.pipelines.get(tool.name, tool.name)
        system = self._system_for(tool.name)
        if not system:
            # No AI system for this tool: there is nothing Prudence could have approved.
            return {"status": "denied", "decision": "DENY",
                    "message": f"Not permitted by governance: tool {tool.name!r} has no AI system."}

        # The gate is synchronous (httpx); keep the event loop free while it waits.
        decision = await asyncio.to_thread(self.gate.decide, self.runtime, pipeline, system, "run")
        if not decision.allowed:
            return {"status": "denied", "decision": decision.decision, "reason": decision.reason,
                    "approver": decision.approver, "decisionId": decision.decision_id,
                    "message": decision.refusal()}

        # Best effort, never blocks or fails the tool.
        execution_id = f"{tool_context.invocation_id}:{tool_context.function_call_id}"
        loop = asyncio.get_running_loop()
        loop.run_in_executor(None, lambda: self.gate.report_execution(
            decision, system, pipeline, runtime=self.runtime, execution_id=execution_id))
        if self.evidence is not None:
            loop.run_in_executor(None, lambda: self.evidence.log_execution(
                system, pipeline=pipeline, runtime=self.runtime, decision_id=decision.decision_id,
                inputs=tool_args))
        return None
