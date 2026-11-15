#!/usr/bin/env python3
"""Conformance kit: does an adapter follow the Prudence adapter contract (contract/adapter/)?

The kit plays Prudence (answers from contract/adapter/vectors/adapter.json) and a scripted model
(../conformance/fake_llm.py), then runs YOUR agent once per case and checks what your adapter
asked, whether the tool ran, whether the model was told, and what it reported. No Prudence, no
model, no API key, any language: the agent is just a command.

    integrations/conformance-kit/kit.py -- python my_agent.py
    integrations/conformance-kit/kit.py --case DENY -- node dist/agent.js
    integrations/conformance-kit/kit.py --list

Your agent reads PRUDENCE_PDP_URL, LLM_URL, AI_SYSTEM_ID (empty in one case) and TOOL, gives an
agent of its framework one tool named TOOL governed by your adapter with no pipeline mapping, asks
it to use the tool, and prints as its last JSON line {"toolRan": bool, "answer": str}. See
README.md. Standard library only; exits 1 if any case fails.
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import socket
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
VECTORS = HERE.parent.parent / "contract" / "adapter" / "vectors" / "adapter.json"
sys.path.insert(0, str(HERE.parent / "conformance"))
from fake_llm import serve as serve_model  # noqa: E402

# Variables the agent must not inherit: the kit's Prudence is open, and a real one must not be hit.
SCRUB = ("PRUDENCE_PDP_TOKEN_URL", "PRUDENCE_PDP_CLIENT_ID", "PRUDENCE_PDP_CLIENT_SECRET",
         "PRUDENCE_PDP_SCOPE", "ERNEST_URL", "ERNEST_API_KEY", "ERNEST_INGESTOR_URL",
         "ERNEST_INGESTOR_KEY", "PIPELINE", "LLM_API_KEY", "LLM_MODEL")


class FakePrudence:
    """Answers POST /api/policy/decisions with the current case and records every request."""

    def __init__(self):
        self.case: dict = {}
        self.decisions: list[dict] = []
        self.reports: list[dict] = []
        self.lock = threading.Lock()
        kit = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                try:
                    body = json.loads(raw or b"null")
                except ValueError:
                    body = {"unreadable": raw[:200].decode(errors="replace")}
                if self.path.startswith("/api/policy/decisions"):
                    with kit.lock:
                        kit.decisions.append(body)
                    answer = kit.case.get("prudence", {})
                    payload = answer["raw"] if "raw" in answer else json.dumps(answer.get("body"))
                    self._send(answer.get("status", 500), payload)
                elif self.path.startswith("/api/executions"):
                    with kit.lock:
                        kit.reports.append(body)
                    self._send(kit.case.get("report", 201), json.dumps({"id": "kit-report"}))
                else:
                    self._send(404, json.dumps({"error": "not part of the kit"}))

            def do_GET(self):
                self._send(404, json.dumps({"error": "not part of the kit"}))

            def _send(self, status: int, payload: str):
                data = payload.encode() if payload else b""
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def arm(self, case: dict) -> None:
        with self.lock:
            self.case, self.decisions, self.reports = case, [], []


def closed_port_url() -> str:
    """A URL nothing listens on: the port was free a moment ago and is closed again."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    return f"http://127.0.0.1:{port}"


def run_agent(command: list[str], env: dict, timeout: int) -> dict:
    base = {k: v for k, v in os.environ.items() if k not in SCRUB}
    try:
        proc = subprocess.run(command, env={**base, **env}, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return {"error": f"agent did not finish in {timeout}s"}
    for line in reversed(proc.stdout.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line)
            except ValueError:
                continue
    return {"error": (proc.stderr.strip().splitlines() or ["no JSON line on stdout"])[-1]}


def check(case: dict, expect: dict, out: dict, prudence: FakePrudence) -> list[str]:
    """What the case expected and did not get; empty when it passes."""
    problems = []
    if "asks" in expect:
        asked = len(prudence.decisions)
        if expect["asks"] and asked != 1:
            problems.append(f"expected one decision request, got {asked}")
        if not expect["asks"] and asked:
            problems.append(f"expected no decision request, got {asked}")
        if expect["asks"] and asked == 1:
            sent = prudence.decisions[0] or {}
            for field, value in (expect.get("request") or {}).items():
                if sent.get(field) != value:
                    problems.append(f"asked {field}={sent.get(field)!r}, expected {value!r}")
    if out.get("toolRan") is not expect["toolRuns"]:
        problems.append(f"tool {'ran' if out.get('toolRan') else 'did not run'}")
    told = expect.get("modelTold")
    if told and told not in str(out.get("answer") or ""):
        problems.append(f"model not told {told}")
    if "reports" in expect:
        cited = [r for r in prudence.reports if isinstance(r, dict) and r.get("decisionId")]
        if expect["reports"]:
            want = expect.get("decisionId")
            if not any(r.get("decisionId") == want for r in cited):
                problems.append(f"no execution report citing {want}")
            elif expect.get("request"):
                pipeline = expect["request"].get("pipeline")
                if not any(r.get("pipeline") == pipeline for r in cited):
                    problems.append(f"report does not name pipeline {pipeline}")
        elif prudence.reports:
            problems.append(f"{len(prudence.reports)} execution report(s) sent after a refusal")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser(description="Run an adapter's agent against the Prudence adapter contract.")
    ap.add_argument("--case", action="append", default=[], help="only cases whose name contains this (repeatable)")
    ap.add_argument("--list", action="store_true", help="list the cases and exit")
    ap.add_argument("--timeout", type=int, default=180, help="seconds per agent run (default 180)")
    ap.add_argument("command", nargs=argparse.REMAINDER, help="-- then the command that runs your agent")
    args = ap.parse_args()

    vectors = json.loads(VECTORS.read_text())
    cases = [c for c in vectors["cases"] if not args.case or any(k in c["name"] for k in args.case)]
    if args.list:
        for c in cases:
            print(c["name"])
        return 0
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        ap.error("give the agent command after --, e.g. kit.py -- python my_agent.py")
    if len(command) == 1 and " " in command[0]:
        command = shlex.split(command[0])

    _, llm_url = serve_model()
    prudence = FakePrudence()
    tool = vectors["tool"]
    rows, failures = [], 0
    for case in cases:
        prudence.arm(case)
        url = closed_port_url() if case["prudence"].get("unreachable") else prudence.url
        system = case.get("aiSystemId", vectors["aiSystemId"])
        env = {"PRUDENCE_PDP_URL": url, "PRUDENCE_URL": url, "LLM_URL": llm_url,
               "AI_SYSTEM_ID": system, "TOOL": tool, "DEMO_TOOL": tool, "PIPELINE": ""}
        out = run_agent(command, env, args.timeout)
        if "error" in out:
            problems = [f"agent failed: {out['error'][:120]}"]
        else:
            problems = check(case, case["expect"], out, prudence)
        failures += bool(problems)
        rows.append((case["name"], "FAIL" if problems else "PASS", "; ".join(problems)))

    width = max(len(r[0]) for r in rows)
    for name, verdict, detail in rows:
        print(f"{name:{width}}  {verdict}  {detail}")
    print(f"\n{len(rows) - failures}/{len(rows)} cases passed ({vectors['version']})")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
