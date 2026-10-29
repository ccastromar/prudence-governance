"""The Policy Enforcement Point as a Semantic Kernel function invocation filter.

A function invocation filter wraps every kernel function call, including the ones the model
makes through automatic function calling; the function runs only when the filter awaits
`next(context)`. That is the gate: ALLOW awaits it, anything else sets the refusal as the
function's result and returns, so the model reads it instead of the output.

The kernel runs the filter added last first. Add this filter before any filter that changes
arguments, so it decides on the arguments the function will actually get.

Which pipeline a function is governed as: `pipelines["Plugin-function"]` or
`pipelines["function"]` when given, otherwise the function's name. Grant the runtime those
pipelines in Prudence (`scripts/provision-runtime.py`); a function nobody granted is denied.
"""
from __future__ import annotations

import asyncio
import uuid
from typing import Any, Awaitable, Callable, Mapping

from semantic_kernel.filters import FunctionInvocationContext
from semantic_kernel.functions import FunctionResult

from prudence_governance import ErnestEvidence, PrudenceGate


class PrudenceFilter:
    def __init__(
        self,
        ai_system_id: str | Mapping[str, str],
        *,
        gate: PrudenceGate | None = None,
        pipelines: Mapping[str, str] | None = None,
        ungoverned: set[str] | frozenset[str] = frozenset(),
        evidence: ErnestEvidence | None = None,
        runtime: str = "semantic-kernel",
    ):
        """`ai_system_id`: the registry id the agent acts as, or one per function name.
        `pipelines`, `ai_system_id` and `ungoverned` take the fully qualified name
        ("Plugin-function") or the bare function name.
        `evidence`: with Ernest in the suite, also seal each allowed execution there."""
        self.gate = gate or PrudenceGate()
        self.ai_system_id = ai_system_id
        self.pipelines = dict(pipelines or {})
        self.ungoverned = frozenset(ungoverned)
        self.evidence = evidence
        self.runtime = runtime

    @staticmethod
    def _lookup(mapping: Mapping[str, str], qualified: str, name: str) -> str | None:
        return mapping.get(qualified) or mapping.get(name)

    async def __call__(
        self,
        context: FunctionInvocationContext,
        next: Callable[[FunctionInvocationContext], Awaitable[None]],
    ) -> None:
        name = context.function.name
        qualified = context.function.fully_qualified_name
        if qualified in self.ungoverned or name in self.ungoverned:
            await next(context)
            return
        pipeline = self._lookup(self.pipelines, qualified, name) or name
        system = (self.ai_system_id if isinstance(self.ai_system_id, str)
                  else self._lookup(self.ai_system_id, qualified, name))
        if not system:
            self._refuse(context, f"Not permitted by governance: function {qualified!r} has no AI system.")
            return

        # The gate is synchronous (httpx); keep the event loop free while it waits.
        decision = await asyncio.to_thread(self.gate.decide, self.runtime, pipeline, system, "run")
        if not decision.allowed:
            self._refuse(context, decision.refusal())
            return

        # Best effort, never blocks or fails the function.
        loop = asyncio.get_running_loop()
        loop.run_in_executor(None, lambda: self.gate.report_execution(
            decision, system, pipeline, runtime=self.runtime, execution_id=str(uuid.uuid4())))
        if self.evidence is not None:
            inputs = self._inputs(context)
            loop.run_in_executor(None, lambda: self.evidence.log_execution(
                system, pipeline=pipeline, runtime=self.runtime, decision_id=decision.decision_id,
                inputs=inputs))
        await next(context)

    @staticmethod
    def _inputs(context: FunctionInvocationContext) -> dict[str, Any]:
        # Only the function's own parameters: KernelArguments can also carry the kernel's
        # execution settings, which are not part of the call.
        params = context.function.metadata.parameters
        return {p.name: context.arguments[p.name] for p in params if p.name in context.arguments}

    @staticmethod
    def _refuse(context: FunctionInvocationContext, message: str) -> None:
        # Not awaiting next() skips the function; the result stands in for its output.
        context.result = FunctionResult(function=context.function.metadata, value=message)
