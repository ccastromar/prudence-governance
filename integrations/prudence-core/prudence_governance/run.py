"""One agent run, one flow id; one tool call, one governed pipeline.

`run()` gives every decision taken inside it the same `correlationId` (Decision API v1): in
Prudence, and in the evidence each decision leaves in Ernest, the calls of one agent run then
read as one flow. Reports and execution evidence cite their decision, so they join the flow
through it.

    with prudence_governance.run():               # a fresh id
        agent.run("…")
    with prudence_governance.run("ticket-4711"):  # or the id of your own business flow
        await agent.run("…")

The id lives in a context variable: it follows `await`, `asyncio` tasks and
`asyncio.to_thread`, but not a thread the framework starts by itself. For those, give the
gate a fixed one (`PrudenceGate(correlation_id=…)`) or pass it to `decide`.

`tool_target()` is how every adapter names a tool call to Prudence (contract/adapter/): the
pipeline `tool:<name>` with the action `tools/call`, the same grant an AI or MCP gateway
uses, unless the integrator maps the tool to a pipeline of their own (then `run`).
"""
from __future__ import annotations

import contextvars
import re
import uuid
from contextlib import contextmanager
from typing import Iterator, Mapping

TOOL_ACTION = "tools/call"
RUN_ACTION = "run"

_CORRELATION = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
_current: contextvars.ContextVar[str | None] = contextvars.ContextVar("prudence_correlation_id", default=None)


def check_correlation_id(value: str) -> str:
    """The id itself, or ValueError: Prudence accepts only a plain token of at most 128
    characters (`[A-Za-z0-9._:-]`), and refuses the whole decision otherwise."""
    if not isinstance(value, str) or not _CORRELATION.match(value):
        raise ValueError("correlation id must match [A-Za-z0-9._:-]{1,128}")
    return value


def current_correlation_id() -> str | None:
    """The flow id of the enclosing `run()`, if any."""
    return _current.get()


@contextmanager
def run(correlation_id: str | None = None) -> Iterator[str]:
    """Run an agent as one flow: every decision inside carries `correlation_id` (a new one
    when not given). Yields the id, e.g. to log it next to your own run."""
    value = check_correlation_id(correlation_id) if correlation_id else f"run-{uuid.uuid4()}"
    token = _current.set(value)
    try:
        yield value
    finally:
        _current.reset(token)


def tool_target(tool: str, pipelines: Mapping[str, str] | None = None) -> tuple[str, str]:
    """(pipeline, action) a call to `tool` is governed as: `pipelines[tool]` with `run` when
    the integrator mapped it, otherwise `tool:<tool>` with `tools/call`."""
    if pipelines and tool in pipelines:
        return pipelines[tool], RUN_ACTION
    return f"tool:{tool}", TOOL_ACTION
