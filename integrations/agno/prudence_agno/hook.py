"""The Policy Enforcement Point as an Agno tool hook.

Agno chains `tool_hooks` around every tool call, middleware style: a hook gets the next
function in the chain and the tool runs only when the hook calls it. That is the gate: ALLOW
calls it, anything else returns the refusal, which becomes the tool's result.

The hook is synchronous on purpose: Agno skips async hooks when the agent runs synchronously
(`agent.run`), and a sync hook works in both modes (`arun` awaits what it returns).

Which pipeline a tool is governed as: `pipelines[tool name]` when given, otherwise the tool's
name. Grant the runtime those pipelines in Prudence (`scripts/provision-runtime.py`); a tool
nobody granted is denied.
"""
from __future__ import annotations

import uuid
from typing import Any, Callable, Mapping

from prudence_governance import ErnestEvidence, PrudenceGate


class PrudenceToolHook:
    def __init__(
        self,
        ai_system_id: str | Mapping[str, str],
        *,
        gate: PrudenceGate | None = None,
        pipelines: Mapping[str, str] | None = None,
        ungoverned: set[str] | frozenset[str] = frozenset(),
        evidence: ErnestEvidence | None = None,
        runtime: str = "agno",
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

    # Agno passes hook arguments by parameter name.
    def __call__(self, function_name: str, function_call: Callable[..., Any],
                 arguments: dict[str, Any]) -> Any:
        tool = function_name
        if tool in self.ungoverned:
            return function_call(**arguments)
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
                                        decision_id=decision.decision_id, inputs=dict(arguments))
        return function_call(**arguments)
