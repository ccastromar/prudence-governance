"""A governed demo runner: demo runner protocol v1, for whichever frameworks its image holds.

Prudence (DemoRunController) is the only caller; the browser never reaches a runner. Every call
carries the runner's bridge token (`X-Prudence-Demo-Runner-Token`).

    GET  /demos                 the demos this runner offers (its manifest)
    POST /demos/{id}/runs       {aiSystemId}; header X-Prudence-Demo-Initiator -> 202 {runId}
    GET  /runs/{runId}          {status: running|done|failed, decision, continued, output, model, …}
    GET  /runs                  the latest run of each demo
    GET  /healthz               no token; "ok"

A run starts the framework's conformance agent in a fresh process (so per-process state, such
as CrewAI's global hooks, never leaks between runs) with the PDP credentials of that framework:
`PRUDENCE_PDP_CLIENTS_FILE` maps demo id -> {clientId, clientSecret}; without an entry the
runner's own PRUDENCE_PDP_CLIENT_ID / _SECRET are used. The decision comes back from Prudence;
the console reads Prudence's own record of it, not this runner's account.

Environment: PORT (9100), PRUDENCE_DEMO_RUNNER_TOKEN, PRUDENCE_URL, PRUDENCE_PDP_TOKEN_URL,
PRUDENCE_PDP_CLIENT_ID, PRUDENCE_PDP_CLIENT_SECRET, PRUDENCE_PDP_CLIENTS_FILE, ERNEST_URL,
ERNEST_API_KEY, PRUDENCE_DEMO_PIPELINE (demo_customer_letter), DEMOS (comma list; default all
installed), PRUDENCE_DEMO_LLM_BASE_URL / _MODEL / _API_KEY (an OpenAI-compatible endpoint; without
one, the scripted model runs in-process), AGENTS_DIR and CONFORMANCE_DIR (default: next to this
folder, as in the repository and the image).
"""
from __future__ import annotations

import hmac
import importlib.util
import json
import os
import re
import subprocess
import sys
import threading
import time
import uuid
from collections import OrderedDict
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from catalog import DEMOS, Demo

HERE = Path(__file__).resolve().parent
CONFORMANCE = Path(os.getenv("CONFORMANCE_DIR", HERE.parent / "conformance"))
AGENTS = Path(os.getenv("AGENTS_DIR", CONFORMANCE / "agents"))
PORT = int(os.getenv("PORT", "9100"))
TOKEN = os.getenv("PRUDENCE_DEMO_RUNNER_TOKEN", "")
PIPELINE = os.getenv("PRUDENCE_DEMO_PIPELINE", "demo_customer_letter")
LLM_BASE_URL = os.getenv("PRUDENCE_DEMO_LLM_BASE_URL", "").strip().rstrip("/")
LLM_MODEL = os.getenv("PRUDENCE_DEMO_LLM_MODEL", "aos-deterministic-mock").strip()
LLM_API_KEY = os.getenv("PRUDENCE_DEMO_LLM_API_KEY", "deterministic-demo-only").strip()
RUN_TIMEOUT = int(os.getenv("PRUDENCE_DEMO_RUN_TIMEOUT", "180"))
KEEP_RUNS = 200

# An agent's answer carries the refusal ("… (Prudence APPROVAL_REQUIRED): …", or "⛔ Governance
# DENY: …" from the Pydantic AI and LangGraph adapters).
VERDICT = re.compile(r"\b(?:Prudence|Governance) (ALLOW|DENY|APPROVAL_REQUIRED|ERROR)\b")


def offered() -> list[Demo]:
    wanted = {d.strip() for d in os.getenv("DEMOS", "").split(",") if d.strip()}
    return [d for d in DEMOS if (not wanted or d.id in wanted) and importlib.util.find_spec(d.adapter)]


def clients() -> dict[str, dict[str, str]]:
    path = os.getenv("PRUDENCE_PDP_CLIENTS_FILE", "")
    return json.loads(Path(path).read_text()) if path else {}


_scripted: dict[str, str] = {}


def scripted_url() -> str:
    """The scripted model (no language model), started in-process on first use."""
    if "url" not in _scripted:
        sys.path.insert(0, str(CONFORMANCE))
        from fake_llm import serve

        _, _scripted["url"] = serve()
    return _scripted["url"]


def model_for(demo: Demo) -> tuple[dict[str, str], str]:
    """The agent's model environment and the label the console shows."""
    if LLM_BASE_URL and not demo.anthropic:
        return ({"LLM_URL": LLM_BASE_URL, "LLM_MODEL": LLM_MODEL, "LLM_API_KEY": LLM_API_KEY},
                f"{LLM_MODEL} via {LLM_BASE_URL}")
    return ({"LLM_URL": scripted_url(), "LLM_MODEL": "conformance-scripted", "LLM_API_KEY": "none"},
            "conformance-scripted (scripted, in-process; not a language model)")


def now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class Runs:
    def __init__(self):
        self.lock = threading.Lock()
        self.by_id: OrderedDict[str, dict[str, Any]] = OrderedDict()
        self.latest: dict[str, str] = {}
        self.slots = threading.BoundedSemaphore(int(os.getenv("PRUDENCE_DEMO_CONCURRENCY", "2")))

    def start(self, demo: Demo, system: str, initiator: str) -> dict[str, Any]:
        run = {"runId": str(uuid.uuid4()), "demo": demo.id, "framework": demo.framework,
               "aiSystemId": system, "pipeline": PIPELINE, "initiator": initiator,
               "status": "running", "startedAt": now()}
        with self.lock:
            self.by_id[run["runId"]] = run
            self.latest[demo.id] = run["runId"]
            while len(self.by_id) > KEEP_RUNS:
                self.by_id.popitem(last=False)
        threading.Thread(target=self._execute, args=(demo, run), daemon=True).start()
        return dict(run)

    def get(self, run_id: str) -> dict[str, Any] | None:
        with self.lock:
            run = self.by_id.get(run_id)
            return dict(run) if run else None

    def latest_runs(self) -> dict[str, dict[str, Any]]:
        with self.lock:
            return {demo: dict(self.by_id[r]) for demo, r in self.latest.items() if r in self.by_id}

    def _finish(self, run: dict[str, Any], **fields):
        with self.lock:
            run.update(fields, finishedAt=now())

    def _execute(self, demo: Demo, run: dict[str, Any]):
        with self.slots:
            try:
                model_env, label = model_for(demo)
                creds = clients().get(demo.id, {})
                env = {
                    **os.environ, **model_env,
                    "AI_SYSTEM_ID": run["aiSystemId"], "PIPELINE": PIPELINE,
                    "PRUDENCE_PDP_CLIENT_ID": creds.get("clientId", os.getenv("PRUDENCE_PDP_CLIENT_ID", "")),
                    "PRUDENCE_PDP_CLIENT_SECRET": creds.get("clientSecret", os.getenv("PRUDENCE_PDP_CLIENT_SECRET", "")),
                }
                proc = subprocess.run([sys.executable, "-I", str(AGENTS / demo.agent)], env=env,
                                      capture_output=True, text=True, timeout=RUN_TIMEOUT)
                line = next((l for l in reversed(proc.stdout.splitlines()) if l.startswith("{")), "")
                if not line:
                    tail = (proc.stderr.strip().splitlines() or ["no output"])[-1]
                    print(f"[{demo.id}] run {run['runId']} failed: {proc.stderr[-2000:]}", file=sys.stderr)
                    self._finish(run, status="failed", error=f"the agent did not finish: {tail[:200]}", model=label)
                    return
                out = json.loads(line)
                answer = str(out.get("answer") or "")
                found = VERDICT.search(answer)
                decision = "ALLOW" if out.get("toolRan") else (found.group(1) if found else "DENY")
                self._finish(run, status="done", decision=decision, continued=bool(out.get("toolRan")),
                             output=answer[:2000], model=label)
            except subprocess.TimeoutExpired:
                self._finish(run, status="failed", error=f"the agent ran longer than {RUN_TIMEOUT}s")
            except Exception as error:  # noqa: BLE001 — a run fails closed and says so
                print(f"[{demo.id}] run {run['runId']} failed: {error!r}", file=sys.stderr)
                self._finish(run, status="failed", error="the governed runtime failed")


RUNS = Runs()


class Handler(BaseHTTPRequestHandler):
    def _authorized(self) -> bool:
        return bool(TOKEN) and hmac.compare_digest(self.headers.get("X-Prudence-Demo-Runner-Token", ""), TOKEN)

    def do_GET(self):  # noqa: N802
        if self.path == "/healthz":
            return self.reply(HTTPStatus.OK, {"status": "ok"})
        if not self._authorized():
            return self.reply(HTTPStatus.UNAUTHORIZED, {"error": "runner token required"})
        if self.path == "/demos":
            return self.reply(HTTPStatus.OK, {"protocol": "prudence.demo-runner/v1",
                                              "demos": [d.manifest(PIPELINE) for d in offered()]})
        if self.path == "/runs":
            return self.reply(HTTPStatus.OK, RUNS.latest_runs())
        if self.path.startswith("/runs/"):
            run = RUNS.get(self.path.removeprefix("/runs/"))
            return self.reply(HTTPStatus.OK, run) if run else self.reply(HTTPStatus.NOT_FOUND, {"error": "unknown run"})
        self.reply(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def do_POST(self):  # noqa: N802
        if not self._authorized():
            return self.reply(HTTPStatus.UNAUTHORIZED, {"error": "runner token required"})
        match = re.fullmatch(r"/demos/([a-z0-9-]+)/runs", self.path)
        demo = next((d for d in offered() if match and d.id == match.group(1)), None)
        if demo is None:
            return self.reply(HTTPStatus.NOT_FOUND, {"error": "unknown demo"})
        try:
            payload = json.loads(self.read_body() or b"{}")
        except ValueError:
            return self.reply(HTTPStatus.BAD_REQUEST, {"error": "body must be JSON"})
        system = payload.get("aiSystemId")
        if not isinstance(system, str) or not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", system):
            return self.reply(HTTPStatus.BAD_REQUEST, {"error": "aiSystemId is required"})
        initiator = self.headers.get("X-Prudence-Demo-Initiator", "unknown")[:200]
        self.reply(HTTPStatus.ACCEPTED, RUNS.start(demo, system, initiator))

    def read_body(self) -> bytes:
        """Request body, with or without Content-Length (HTTP/1.1 chunked transfer encoding)."""
        if "chunked" not in self.headers.get("Transfer-Encoding", "").lower():
            return self.rfile.read(int(self.headers.get("Content-Length", "0") or 0))
        body = bytearray()
        while True:
            size = int(self.rfile.readline().split(b";", 1)[0].strip() or b"0", 16)
            if size == 0:
                while self.rfile.readline() not in (b"\r\n", b"\n", b""):
                    pass  # optional trailers
                return bytes(body)
            body += self.rfile.read(size)
            self.rfile.readline()  # CRLF after each chunk

    def reply(self, status: HTTPStatus, body: Any):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, _format, *_args):
        pass


if __name__ == "__main__":
    if not TOKEN:
        sys.exit("PRUDENCE_DEMO_RUNNER_TOKEN is required")
    demos = offered()
    print(f"demo runner on :{PORT}: {', '.join(d.id for d in demos) or 'no demos installed'}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
