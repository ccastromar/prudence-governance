"""The Policy Enforcement Point as Microsoft Agent Framework function middleware.

Function middleware wraps each tool call; the tool runs only when the middleware calls
`call_next()`. That is the gate: ALLOW calls it, anything else sets the refusal as the result
and returns (the agent keeps going and tells the user), or raises `MiddlewareTermination` to
end the run when `stop_on_deny` is set.

Register it after any argument-repair middleware: the framework fails closed if arguments
change after a policy layer has seen them.

Which pipeline a tool is governed as: `pipelines[tool name]` when given, otherwise the tool's
name. Grant the runtime those pipelines in Prudence (`scripts/provision-runtime.py`).
"""
from __future__ import annotations

import asyncio
import uuid
from typing import Awaitable, Callable, Mapping

from agent_framework import FunctionInvocationContext, FunctionMiddleware, MiddlewareTermination

from prudence_governance import PrudenceGate


class PrudenceMiddleware(FunctionMiddleware):
    def __init__(
        self,
        ai_system_id: str | Mapping[str, str],
        *,
        gate: PrudenceGate | None = None,
        pipelines: Mapping[str, str] | None = None,
        ungoverned: set[str] | frozenset[str] = frozenset(),
        stop_on_deny: bool = False,
        runtime: str = "agent-framework",
    ):
        """`ai_system_id`: the registry id the agent acts as, or one per tool name.
        `ungoverned`: tool names that need no decision (read-only helpers, for example)."""
        self.gate = gate or PrudenceGate()
        self.ai_system_id = ai_system_id
        self.pipelines = dict(pipelines or {})
        self.ungoverned = frozenset(ungoverned)
        self.stop_on_deny = stop_on_deny
        self.runtime = runtime

    async def process(
        self, context: FunctionInvocationContext, call_next: Callable[[], Awaitable[None]]
    ) -> None:
        tool = context.function.name
        if tool in self.ungoverned:
            await call_next()
            return
        pipeline = self.pipelines.get(tool, tool)
        system = self.ai_system_id if isinstance(self.ai_system_id, str) else self.ai_system_id.get(tool)
        if not system:
            self._refuse(context, f"Not permitted by governance: tool {tool!r} has no AI system.")
            return

        # The gate is synchronous (httpx); keep the event loop free while it waits.
        decision = await asyncio.to_thread(self.gate.decide, self.runtime, pipeline, system, "run")
        if not decision.allowed:
            self._refuse(context, decision.refusal())
            return

        # Best effort, never blocks or fails the tool.
        asyncio.get_running_loop().run_in_executor(
            None,
            lambda: self.gate.report_execution(decision, system, pipeline, runtime=self.runtime,
                                               execution_id=str(uuid.uuid4())),
        )
        await call_next()

    def _refuse(self, context: FunctionInvocationContext, message: str) -> None:
        # Not calling call_next() skips the tool; the result stands in for its output.
        context.result = message
        if self.stop_on_deny:
            raise MiddlewareTermination(message, result=message)
