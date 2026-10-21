"""The Policy Enforcement Point as an OpenAI Agents SDK tool input guardrail.

The SDK runs a tool's input guardrails before the tool. A guardrail answers allow,
reject_content (skip the tool, tell the model why, keep the run going) or raise_exception
(stop the run). That is the gate: ALLOW allows, anything else rejects.

Which pipeline a tool is governed as: `pipelines[tool name]` when given, otherwise the tool's
name. Grant the runtime those pipelines in Prudence (`scripts/provision-runtime.py`).
"""
from __future__ import annotations

import asyncio
from typing import Literal, Mapping

from agents import FunctionTool, ToolGuardrailFunctionOutput, ToolInputGuardrail, ToolInputGuardrailData

from prudence_governance import ErnestEvidence, PrudenceGate


def prudence_guardrail(
    ai_system_id: str | Mapping[str, str],
    *,
    gate: PrudenceGate | None = None,
    pipelines: Mapping[str, str] | None = None,
    on_deny: Literal["reject", "raise"] = "reject",
    evidence: ErnestEvidence | None = None,
    runtime: str = "openai-agents",
) -> ToolInputGuardrail:
    """`ai_system_id`: the registry id the agent acts as, or one per tool name.
    `evidence`: with Ernest in the suite, also seal each allowed execution there."""
    gate = gate or PrudenceGate()
    pipelines = dict(pipelines or {})

    async def check(data: ToolInputGuardrailData) -> ToolGuardrailFunctionOutput:
        ctx = data.context
        tool = ctx.tool_name
        pipeline = pipelines.get(tool, tool)
        system = ai_system_id if isinstance(ai_system_id, str) else ai_system_id.get(tool)
        if not system:
            message = f"Not permitted by governance: tool {tool!r} has no AI system."
            info = {"decision": "DENY", "reason": "no AI system for this tool"}
        else:
            # The gate is synchronous (httpx); keep the event loop free while it waits.
            decision = await asyncio.to_thread(gate.decide, runtime, pipeline, system, "run")
            info = {"decision": decision.decision, "reason": decision.reason,
                    "approver": decision.approver, "decisionId": decision.decision_id}
            if decision.allowed:
                # Best effort, never blocks or fails the tool.
                loop = asyncio.get_running_loop()
                loop.run_in_executor(None, lambda: gate.report_execution(
                    decision, system, pipeline, runtime=runtime, execution_id=ctx.tool_call_id or None))
                if evidence is not None:
                    loop.run_in_executor(None, lambda: evidence.log_execution(
                        system, pipeline=pipeline, runtime=runtime, decision_id=decision.decision_id,
                        inputs=ctx.tool_arguments))
                return ToolGuardrailFunctionOutput.allow(output_info=info)
            message = decision.refusal()
        if on_deny == "raise":
            return ToolGuardrailFunctionOutput.raise_exception(output_info=info)
        return ToolGuardrailFunctionOutput.reject_content(message, output_info=info)

    return ToolInputGuardrail(guardrail_function=check, name="prudence")


def govern(tool: FunctionTool, guardrail: ToolInputGuardrail) -> FunctionTool:
    """Put the guardrail in front of a tool you did not define (it runs before the others)."""
    tool.tool_input_guardrails = [guardrail, *(tool.tool_input_guardrails or [])]
    return tool
