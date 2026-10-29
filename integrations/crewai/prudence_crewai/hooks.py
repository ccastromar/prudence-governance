"""The Policy Enforcement Point as CrewAI tool call hooks.

CrewAI runs every `before_tool_call` hook before a tool; when one returns False, the tool is
skipped. That is the gate: ALLOW returns None (the tool runs), anything else returns False.
CrewAI then hands the model a generic "blocked by hook" message; the matching
`after_tool_call` hook swaps it for Prudence's refusal, so the model learns why.

CrewAI swallows an exception raised by a hook and runs the tool anyway, so the before hook
never raises: any failure of its own is a refusal.

The hooks are process-wide (CrewAI keeps one list), so they govern every crew and agent in
the process. Which pipeline a tool is governed as: `pipelines[tool name]` when given,
otherwise the tool's name. Grant the runtime those pipelines in Prudence
(`scripts/provision-runtime.py`); a tool nobody granted is denied.
"""
from __future__ import annotations

import threading
import uuid
from typing import Mapping

from crewai.hooks import (
    ToolCallHookContext,
    register_after_tool_call_hook,
    register_before_tool_call_hook,
    unregister_after_tool_call_hook,
    unregister_before_tool_call_hook,
)

from prudence_governance import ErnestEvidence, PrudenceGate

_BLOCKED = "Tool execution blocked by hook"


class PrudenceToolHooks:
    def __init__(
        self,
        ai_system_id: str | Mapping[str, str],
        *,
        gate: PrudenceGate | None = None,
        pipelines: Mapping[str, str] | None = None,
        ungoverned: set[str] | frozenset[str] = frozenset(),
        evidence: ErnestEvidence | None = None,
        runtime: str = "crewai",
    ):
        """`ai_system_id`: the registry id the crew acts as, or one per tool name.
        `ungoverned`: tool names that need no decision (read-only helpers, for example).
        `evidence`: with Ernest in the suite, also seal each allowed execution there."""
        self.gate = gate or PrudenceGate()
        self.ai_system_id = ai_system_id
        self.pipelines = dict(pipelines or {})
        self.ungoverned = frozenset(ungoverned)
        self.evidence = evidence
        self.runtime = runtime
        # The refusal for the call this thread just blocked, for the after hook to show.
        self._refusals = threading.local()

    def register(self) -> "PrudenceToolHooks":
        register_before_tool_call_hook(self.before_tool_call)
        register_after_tool_call_hook(self.after_tool_call)
        return self

    def unregister(self) -> None:
        unregister_before_tool_call_hook(self.before_tool_call)
        unregister_after_tool_call_hook(self.after_tool_call)

    def before_tool_call(self, context: ToolCallHookContext) -> bool | None:
        try:
            refusal = self._check(context)
        except Exception as e:  # noqa: BLE001 — CrewAI would run the tool on a raise
            refusal = f"Not permitted by governance: {e}"
        if refusal is None:
            return None
        self._refusals.message = refusal
        return False

    def after_tool_call(self, context: ToolCallHookContext) -> str | None:
        refusal = getattr(self._refusals, "message", None)
        self._refusals.message = None
        if refusal and (context.tool_result or "").startswith(_BLOCKED):
            return refusal
        return None

    def _check(self, context: ToolCallHookContext) -> str | None:
        """None when the tool may run, otherwise the refusal."""
        tool = context.tool_name
        if tool in self.ungoverned:
            return None
        pipeline = self.pipelines.get(tool, tool)
        system = self.ai_system_id if isinstance(self.ai_system_id, str) else self.ai_system_id.get(tool)
        if not system:
            return f"Not permitted by governance: tool {tool!r} has no AI system."

        decision = self.gate.decide(self.runtime, pipeline, system, "run")
        if not decision.allowed:
            return decision.refusal()

        # Best effort: both calls swallow their own errors.
        self.gate.report_execution(decision, system, pipeline, runtime=self.runtime,
                                   execution_id=str(uuid.uuid4()))
        if self.evidence is not None:
            self.evidence.log_execution(system, pipeline=pipeline, runtime=self.runtime,
                                        decision_id=decision.decision_id,
                                        inputs=dict(context.tool_input or {}))
        return None
