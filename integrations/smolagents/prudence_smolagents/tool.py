"""The Policy Enforcement Point as a smolagents tool wrapper.

smolagents has no hook in front of every tool call (step callbacks run after a step), so the
gate wraps the tool itself: `GovernedTool` copies the wrapped tool's name, description, inputs
and output type (the model sees the same tool) and asks Prudence on each call. ALLOW calls the
wrapped tool; anything else returns the refusal as the tool's result. It works the same for a
`ToolCallingAgent` and for a `CodeAgent`, whose generated code calls the tool as a function.

Wrap every tool you give the agent (`govern_tools`); a tool left unwrapped is not governed.
Which pipeline a tool is governed as: `pipelines[tool name]` when given, otherwise the tool's
name. Grant the runtime those pipelines in Prudence (`scripts/provision-runtime.py`).
"""
from __future__ import annotations

import uuid
from typing import Any, Iterable, Mapping

from smolagents import Tool

from prudence_governance import ErnestEvidence, PrudenceGate


class GovernedTool(Tool):
    # forward takes whatever the wrapped tool takes; its own inputs are checked there.
    skip_forward_signature_validation = True

    def __init__(
        self,
        tool: Tool,
        ai_system_id: str | None,
        *,
        gate: PrudenceGate | None = None,
        pipeline: str | None = None,
        evidence: ErnestEvidence | None = None,
        runtime: str = "smolagents",
    ):
        """`ai_system_id`: the registry id the agent acts as when it calls this tool; None
        denies every call. `pipeline`: what the tool is governed as (default: the tool's name).
        `evidence`: with Ernest in the suite, also seal each allowed execution there."""
        self.name = tool.name
        self.description = tool.description
        self.inputs = tool.inputs
        self.output_type = tool.output_type
        self.output_schema = getattr(tool, "output_schema", None)
        super().__init__()
        self.tool = tool
        self.gate = gate or PrudenceGate()
        self.ai_system_id = ai_system_id
        self.pipeline = pipeline or tool.name
        self.evidence = evidence
        self.runtime = runtime

    def forward(self, *args: Any, **kwargs: Any) -> Any:
        if not self.ai_system_id:
            # No AI system for this tool: there is nothing Prudence could have approved.
            return f"Not permitted by governance: tool {self.name!r} has no AI system."
        decision = self.gate.decide(self.runtime, self.pipeline, self.ai_system_id, "run")
        if not decision.allowed:
            return decision.refusal()

        # Best effort: both calls swallow their own errors.
        self.gate.report_execution(decision, self.ai_system_id, self.pipeline, runtime=self.runtime,
                                   execution_id=str(uuid.uuid4()))
        if self.evidence is not None:
            inputs = {**dict(zip(self.inputs, args)), **kwargs}
            self.evidence.log_execution(self.ai_system_id, pipeline=self.pipeline,
                                        runtime=self.runtime, decision_id=decision.decision_id,
                                        inputs=inputs)
        return self.tool(*args, **kwargs)


def govern_tools(
    tools: Iterable[Tool],
    ai_system_id: str | Mapping[str, str],
    *,
    gate: PrudenceGate | None = None,
    pipelines: Mapping[str, str] | None = None,
    ungoverned: set[str] | frozenset[str] = frozenset(),
    evidence: ErnestEvidence | None = None,
    runtime: str = "smolagents",
) -> list[Tool]:
    """Wrap each tool in a `GovernedTool`, sharing one gate. `ai_system_id` is one id for the
    agent or one per tool name; `ungoverned` tools (read-only helpers) are returned as they
    are. A tool with no AI system is wrapped as denied, so it cannot run ungoverned."""
    gate = gate or PrudenceGate()
    pipelines = dict(pipelines or {})
    governed: list[Tool] = []
    for tool in tools:
        if tool.name in ungoverned:
            governed.append(tool)
            continue
        system = ai_system_id if isinstance(ai_system_id, str) else ai_system_id.get(tool.name)
        governed.append(GovernedTool(tool, system, gate=gate, pipeline=pipelines.get(tool.name),
                                     evidence=evidence, runtime=runtime))
    return governed
