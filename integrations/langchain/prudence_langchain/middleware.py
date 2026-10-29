"""The Policy Enforcement Point as LangChain agent middleware.

`create_agent` runs each tool call through the middleware's `wrap_tool_call` (or
`awrap_tool_call` when the agent runs async); the tool runs only when the middleware calls
`handler(request)`. That is the gate: ALLOW calls it, anything else returns the refusal as an
error `ToolMessage`, which the model reads instead of the tool's output.

Middleware compose first-defined-outermost. Put this one after any middleware that rewrites
tool arguments, so it decides on the arguments the tool will get.

Which pipeline a tool is governed as: `pipelines[tool name]` when given, otherwise the tool's
name. Grant the runtime those pipelines in Prudence (`scripts/provision-runtime.py`); a tool
nobody granted is denied.
"""
from __future__ import annotations

import asyncio
from typing import Any, Awaitable, Callable, Mapping

from langchain.agents.middleware import AgentMiddleware
from langchain_core.messages import ToolMessage
from langgraph.prebuilt.tool_node import ToolCallRequest
from langgraph.types import Command

from prudence_governance import ErnestEvidence, PrudenceGate


class PrudenceMiddleware(AgentMiddleware):
    def __init__(
        self,
        ai_system_id: str | Mapping[str, str],
        *,
        gate: PrudenceGate | None = None,
        pipelines: Mapping[str, str] | None = None,
        ungoverned: set[str] | frozenset[str] = frozenset(),
        evidence: ErnestEvidence | None = None,
        runtime: str = "langchain",
    ):
        """`ai_system_id`: the registry id the agent acts as, or one per tool name.
        `ungoverned`: tool names that need no decision (read-only helpers, for example).
        `evidence`: with Ernest in the suite, also seal each allowed execution there."""
        super().__init__()
        self.gate = gate or PrudenceGate()
        self.ai_system_id = ai_system_id
        self.pipelines = dict(pipelines or {})
        self.ungoverned = frozenset(ungoverned)
        self.evidence = evidence
        self.runtime = runtime

    def wrap_tool_call(
        self,
        request: ToolCallRequest,
        handler: Callable[[ToolCallRequest], ToolMessage | Command[Any]],
    ) -> ToolMessage | Command[Any]:
        refusal = self._check(request)
        if refusal is not None:
            return refusal
        return handler(request)

    async def awrap_tool_call(
        self,
        request: ToolCallRequest,
        handler: Callable[[ToolCallRequest], Awaitable[ToolMessage | Command[Any]]],
    ) -> ToolMessage | Command[Any]:
        # The gate is synchronous (httpx); keep the event loop free while it waits.
        refusal = await asyncio.to_thread(self._check, request)
        if refusal is not None:
            return refusal
        return await handler(request)

    def _check(self, request: ToolCallRequest) -> ToolMessage | None:
        """None when the tool may run, otherwise the refusal as the tool's message."""
        call = request.tool_call
        tool = call["name"]
        if tool in self.ungoverned:
            return None
        pipeline = self.pipelines.get(tool, tool)
        system = self.ai_system_id if isinstance(self.ai_system_id, str) else self.ai_system_id.get(tool)
        if not system:
            # No AI system for this tool: there is nothing Prudence could have approved.
            return self._refusal(call, f"Not permitted by governance: tool {tool!r} has no AI system.")

        decision = self.gate.decide(self.runtime, pipeline, system, "run")
        if not decision.allowed:
            return self._refusal(call, decision.refusal())

        # Best effort: both calls swallow their own errors.
        self.gate.report_execution(decision, system, pipeline, runtime=self.runtime,
                                   execution_id=call.get("id"))
        if self.evidence is not None:
            self.evidence.log_execution(system, pipeline=pipeline, runtime=self.runtime,
                                        decision_id=decision.decision_id,
                                        inputs=dict(call.get("args") or {}))
        return None

    @staticmethod
    def _refusal(call: Mapping[str, Any], message: str) -> ToolMessage:
        return ToolMessage(content=message, tool_call_id=call.get("id") or "", name=call["name"],
                           status="error")
