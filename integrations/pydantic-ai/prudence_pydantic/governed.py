"""The `@governed` decorator — a Policy Enforcement Point for a Pydantic AI tool.

Wrap any Pydantic AI tool with it and the tool becomes governed: before it runs, the
Prudence PDP is consulted (allow / deny / approval-required); when it is allowed, the
execution is reported to Prudence and sealed in Ernest under the AI system, as every other
adapter does. The decision itself is recorded by Prudence (the PDP seals every decision it
makes), so the tool does not record it a second time. The agent framework never sees the
policy. This is the AOS gate as a library primitive — proof that govern+prove is portable to
any runtime, not tied to AOS.

The governed subject (caller identity), the registry system id and the evidence clients
travel in the agent's dependencies (`RunContext.deps`), so the same tool can be run under
different identities and policies without changing its code.
"""
from __future__ import annotations

import functools
from dataclasses import dataclass
from typing import Callable

from pydantic_ai import RunContext

from prudence_governance import Decision, ErnestEvidence, PrudenceGate


@dataclass
class GovDeps:
    subject: str                      # the caller identity the PDP authorizes
    ai_system_id: str                 # the Prudence registry id (approved?)
    gate: PrudenceGate
    evidence: ErnestEvidence | None = None
    # No longer used: executions are sealed under `ai_system_id`, where Prudence counts them.
    model_id: str | None = None


class GovernanceDenied(Exception):
    def __init__(self, decision: Decision):
        self.decision = decision
        super().__init__(f"{decision.decision}: {decision.reason}")


def governed(pipeline: str, action: str = "run", *, raise_on_deny: bool = False):
    def deco(fn: Callable) -> Callable:
        @functools.wraps(fn)
        def wrapper(ctx: RunContext[GovDeps], *args, **kwargs):
            deps: GovDeps = ctx.deps
            decision = deps.gate.decide(deps.subject, pipeline, deps.ai_system_id, action)

            if not decision.allowed:
                if raise_on_deny:
                    raise GovernanceDenied(decision)
                return f"⛔ Governance {decision.decision}: {decision.reason or 'not permitted'}"

            # Best effort: both calls swallow their own errors. The seal retries while Ernest
            # does not know the system yet (Prudence registers it with its first decision).
            deps.gate.report_execution(decision, deps.ai_system_id, pipeline, runtime="pydantic-ai")
            if deps.evidence is not None:
                deps.evidence.log_execution(deps.ai_system_id, pipeline=pipeline, runtime="pydantic-ai",
                                            decision_id=decision.decision_id,
                                            inputs={"args": args, "kwargs": kwargs})
            return fn(ctx, *args, **kwargs)

        return wrapper

    return deco
