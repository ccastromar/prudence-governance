"""Private, deterministic demonstrations for the Prudence console.

The HTTP surface is intentionally tiny and intended only for the Prudence backend on the Docker
application network. It executes the real framework adapters: ``@governed`` for Pydantic AI and
``governed_node`` / ``gate_route`` for LangGraph. Neither an LLM API key nor user data is used.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph
from pydantic_ai import Agent, RunContext
from pydantic_ai.models.test import TestModel

from prudence_governance import ErnestEvidence, M2MAuth, PrudenceGate
from prudence_langgraph import gate_route, governed_node
from prudence_pydantic import GovDeps, governed

PORT = int(os.getenv("PORT", "9100"))
SHARED_TOKEN = os.getenv("PRUDENCE_DEMO_RUNNER_TOKEN", "")
LATEST: dict[str, dict[str, Any]] = {}


class RecordingGate:
    """Records the verdict while delegating the actual PEP decision to Prudence."""

    def __init__(self, delegate: PrudenceGate):
        self.delegate = delegate
        self.last = None

    def decide(self, subject: str, pipeline: str, ai_system_id: str, action: str = "run"):
        self.last = self.delegate.decide(subject, pipeline, ai_system_id, action)
        return self.last


class RecordingEvidence(ErnestEvidence):
    def __init__(self):
        super().__init__(source="prudence-framework-demo")
        self.receipt: dict[str, Any] | None = None

    def log_step(self, *args, **kwargs):
        status, receipt = super().log_step(*args, **kwargs)
        self.receipt = {"status": status, "receipt": receipt}
        return status, receipt


def clients() -> tuple[RecordingGate, RecordingEvidence]:
    auth = M2MAuth.from_env()
    if auth is None:
        raise RuntimeError("PRUDENCE_PDP_TOKEN_URL, _CLIENT_ID and _CLIENT_SECRET are required")
    return RecordingGate(PrudenceGate(auth=auth)), RecordingEvidence()


def stable_priority(summary: str) -> str:
    return "P1" if int(hashlib.sha256(summary.encode()).hexdigest(), 16) % 3 == 0 else "P2"


def run_bug_triage(system_id: str, initiator: str) -> dict[str, Any]:
    gate, evidence = clients()
    model_id = f"prudence-bug-triage-{uuid.uuid4().hex[:12]}"
    evidence.ensure_model(
        model_id,
        "Governed bug triage (Pydantic AI)",
        "1.0.0",
        metadata={"framework": "pydantic-ai", "aiSystemId": system_id, "initiator": initiator},
    )
    agent = Agent(deps_type=GovDeps)

    @agent.tool
    @governed(pipeline="pipeline_bug_triage", action="run")
    def triage(ctx: RunContext[GovDeps], summary: str = "Checkout error after payment") -> str:
        priority = stable_priority(summary)
        return f"priority={priority}; queue=customer-platform; status=triaged"

    deps = GovDeps(
        subject="service-account-prudence-demo-runner",
        ai_system_id=system_id,
        model_id=model_id,
        gate=gate,
        evidence=evidence,
    )
    result = agent.run_sync("Triage the checkout incident", deps=deps, model=TestModel())
    output = getattr(result, "output", None) or getattr(result, "data", None)
    return response("Pydantic AI", "Bug triage tool", gate, evidence, output, ai_system_id=system_id)


class DraftState(TypedDict, total=False):
    subject: str
    ai_system_id: str
    model_id: str
    request: str
    gate: str
    reason: str
    output: str
    sent: bool


def run_response_drafter(system_id: str, initiator: str) -> dict[str, Any]:
    gate, evidence = clients()
    model_id = f"prudence-response-drafter-{uuid.uuid4().hex[:12]}"
    evidence.ensure_model(
        model_id,
        "Governed response drafter (LangGraph)",
        "1.0.0",
        metadata={"framework": "langgraph", "aiSystemId": system_id, "initiator": initiator},
    )

    def draft_node(state: DraftState) -> dict[str, Any]:
        return {"output": "Draft: We are investigating the checkout issue and will update you shortly."}

    def deliver_node(state: DraftState) -> dict[str, Any]:
        return {"sent": True, "output": state["output"] + " Delivery queued."}

    graph = StateGraph(DraftState)
    graph.add_node(
        "draft",
        governed_node(draft_node, pipeline="pipeline_response_drafter", action="run", gate=gate, evidence=evidence),
    )
    graph.add_node("deliver", deliver_node)
    graph.add_edge(START, "draft")
    graph.add_conditional_edges("draft", gate_route, {"allow": "deliver", "stop": END})
    graph.add_edge("deliver", END)
    compiled = graph.compile()
    out = compiled.invoke(
        {
            "subject": "service-account-prudence-demo-runner",
            "ai_system_id": system_id,
            "model_id": model_id,
            "request": "A customer reports a checkout error",
        }
    )
    return response(
        "LangGraph", "Response drafting node", gate, evidence, out.get("output"), out.get("sent", False), system_id
    )


def response(
    framework: str,
    step: str,
    gate: RecordingGate,
    evidence: RecordingEvidence,
    output: Any,
    continued: bool | None = None,
    ai_system_id: str = "",
):
    decision = gate.last
    if decision is None:
        raise RuntimeError("framework did not consult the governance gate")
    body: dict[str, Any] = {
        "framework": framework,
        "step": step,
        "decision": decision.decision,
        "reason": decision.reason,
        "output": str(output) if output is not None else None,
        "aiSystemId": ai_system_id,
        "evidence": evidence.receipt,
    }
    if continued is not None:
        body["continued"] = continued
    return body


class Handler(BaseHTTPRequestHandler):
    def _authorized(self) -> bool:
        return bool(SHARED_TOKEN) and hmac.compare_digest(
            self.headers.get("X-Prudence-Demo-Runner-Token", ""), SHARED_TOKEN
        )

    def do_GET(self):  # noqa: N802
        if not self._authorized():
            self.send_error(HTTPStatus.UNAUTHORIZED, "runner token required")
            return
        if self.path != "/runs":
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        self.reply(HTTPStatus.OK, LATEST)

    def do_POST(self):  # noqa: N802
        if not self._authorized():
            self.send_error(HTTPStatus.UNAUTHORIZED, "runner token required")
            return
        if self.path not in ("/runs/bug-triage", "/runs/response-drafter"):
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length))
            system_id = payload.get("aiSystemId", "")
            if not isinstance(system_id, str) or not system_id.strip():
                raise ValueError("aiSystemId is required")
            initiator = self.headers.get("X-Prudence-Demo-Initiator", "unknown")
            result = run_bug_triage(system_id, initiator) if self.path.endswith("bug-triage") else run_response_drafter(system_id, initiator)
            LATEST[self.path.rsplit("/", 1)[-1]] = result
            self.reply(HTTPStatus.OK, result)
        except ValueError as error:
            self.reply(HTTPStatus.BAD_REQUEST, {"error": str(error)})
        except Exception:  # fail closed; do not return runtime internals to the browser
            self.reply(HTTPStatus.BAD_GATEWAY, {"error": "governed runtime failed"})

    def reply(self, status: HTTPStatus, body: dict[str, Any]):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, _format, *_args):
        pass


if __name__ == "__main__":
    if not SHARED_TOKEN:
        raise SystemExit("PRUDENCE_DEMO_RUNNER_TOKEN is required")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
