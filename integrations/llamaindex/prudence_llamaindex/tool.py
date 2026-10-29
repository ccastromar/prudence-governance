"""The Policy Enforcement Point as a LlamaIndex tool wrapper.

LlamaIndex has no hook in front of every tool call, so the gate wraps the tool itself:
`GovernedTool` keeps the wrapped tool's metadata (the model sees the same name and schema)
and asks Prudence on each `call`/`acall`. ALLOW calls the wrapped tool; anything else returns
the refusal as an error `ToolOutput`, which the agent hands to the model.

Wrap every tool you give the agent (`govern_tools`); a tool left unwrapped is not governed.
Which pipeline a tool is governed as: `pipelines[tool name]` when given, otherwise the tool's
name. Grant the runtime those pipelines in Prudence (`scripts/provision-runtime.py`).
"""
from __future__ import annotations

import asyncio
import uuid
from typing import Any, Iterable, Mapping

from llama_index.core.tools import AsyncBaseTool, BaseTool, ToolMetadata, ToolOutput, adapt_to_async_tool

from prudence_governance import ErnestEvidence, PrudenceGate


class GovernedTool(AsyncBaseTool):
    def __init__(
        self,
        tool: BaseTool,
        ai_system_id: str,
        *,
        gate: PrudenceGate | None = None,
        pipeline: str | None = None,
        evidence: ErnestEvidence | None = None,
        runtime: str = "llamaindex",
    ):
        """`ai_system_id`: the registry id the agent acts as when it calls this tool.
        `pipeline`: what the tool is governed as (default: the tool's name).
        `evidence`: with Ernest in the suite, also seal each allowed execution there."""
        self.tool = adapt_to_async_tool(tool)
        self.gate = gate or PrudenceGate()
        self.ai_system_id = ai_system_id
        self.pipeline = pipeline or tool.metadata.get_name()
        self.evidence = evidence
        self.runtime = runtime

    @property
    def metadata(self) -> ToolMetadata:
        return self.tool.metadata

    def call(self, *args: Any, **kwargs: Any) -> ToolOutput:
        refusal = self._check(args, kwargs)
        if refusal is not None:
            return refusal
        return self.tool.call(*args, **kwargs)

    async def acall(self, *args: Any, **kwargs: Any) -> ToolOutput:
        # The gate is synchronous (httpx); keep the event loop free while it waits.
        refusal = await asyncio.to_thread(self._check, args, kwargs)
        if refusal is not None:
            return refusal
        return await self.tool.acall(*args, **kwargs)

    def _check(self, args: tuple, kwargs: dict[str, Any]) -> ToolOutput | None:
        """None when the tool may run, otherwise the refusal as the tool's output."""
        decision = self.gate.decide(self.runtime, self.pipeline, self.ai_system_id, "run")
        if not decision.allowed:
            return self._refusal(decision.refusal(), args, kwargs)

        # Best effort: both calls swallow their own errors.
        self.gate.report_execution(decision, self.ai_system_id, self.pipeline, runtime=self.runtime,
                                   execution_id=str(uuid.uuid4()))
        if self.evidence is not None:
            inputs = dict(kwargs) if kwargs or not args else {"input": args[0]}
            self.evidence.log_execution(self.ai_system_id, pipeline=self.pipeline,
                                        runtime=self.runtime, decision_id=decision.decision_id,
                                        inputs=inputs)
        return None

    def _refusal(self, message: str, args: tuple, kwargs: dict[str, Any]) -> ToolOutput:
        return ToolOutput(content=message, tool_name=self.metadata.get_name(),
                          raw_input={"args": args, "kwargs": kwargs}, raw_output=message,
                          is_error=True)


def govern_tools(
    tools: Iterable[BaseTool],
    ai_system_id: str | Mapping[str, str],
    *,
    gate: PrudenceGate | None = None,
    pipelines: Mapping[str, str] | None = None,
    ungoverned: set[str] | frozenset[str] = frozenset(),
    evidence: ErnestEvidence | None = None,
    runtime: str = "llamaindex",
) -> list[BaseTool]:
    """Wrap each tool in a `GovernedTool`, sharing one gate. `ai_system_id` is one id for the
    agent or one per tool name; `ungoverned` tools (read-only helpers) are returned as they
    are. A tool with no AI system is wrapped as denied, so it cannot run ungoverned."""
    gate = gate or PrudenceGate()
    pipelines = dict(pipelines or {})
    governed: list[BaseTool] = []
    for tool in tools:
        name = tool.metadata.get_name()
        if name in ungoverned:
            governed.append(tool)
            continue
        system = ai_system_id if isinstance(ai_system_id, str) else ai_system_id.get(name)
        if not system:
            governed.append(_Denied(tool))
            continue
        governed.append(GovernedTool(tool, system, gate=gate, pipeline=pipelines.get(name),
                                     evidence=evidence, runtime=runtime))
    return governed


class _Denied(AsyncBaseTool):
    """A tool with no AI system: there is nothing Prudence could have approved."""

    def __init__(self, tool: BaseTool):
        self._metadata = tool.metadata

    @property
    def metadata(self) -> ToolMetadata:
        return self._metadata

    def call(self, *args: Any, **kwargs: Any) -> ToolOutput:
        message = f"Not permitted by governance: tool {self._metadata.get_name()!r} has no AI system."
        return ToolOutput(content=message, tool_name=self._metadata.get_name(),
                          raw_input={"args": args, "kwargs": kwargs}, raw_output=message,
                          is_error=True)

    async def acall(self, *args: Any, **kwargs: Any) -> ToolOutput:
        return self.call(*args, **kwargs)
