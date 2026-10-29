#!/usr/bin/env python3
"""Conformance run: do the framework adapters really enforce Prudence's decisions?

For each framework in FRAMEWORKS (Pydantic AI, LangGraph, Google ADK, OpenAI Agents SDK, Microsoft Agent Framework,
Semantic Kernel, Strands, LangChain, Claude Agent SDK, CrewAI, Agno, LlamaIndex, smolagents) a
real agent of that framework, with one tool and a scripted model (fake_llm.py), asks to send a letter. Four
cases each:

  ALLOW              approved system, granted pipeline  -> the tool runs and the run is reported
  APPROVAL_REQUIRED  high-risk system awaiting approval -> the tool does not run
  DENY               pipeline the runtime was not granted -> the tool does not run
  PRUDENCE DOWN      Prudence unreachable                -> the tool does not run (fail closed)

Needs the stack from stack.sh (Prudence :18099, OPA :18181) and the per-framework environments
(setup in README.md). Standard library only; exits 1 if any case fails.

    integrations/conformance/run.py
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from fake_llm import serve  # noqa: E402
PRUDENCE = os.getenv("PRUDENCE_URL", "http://127.0.0.1:18099")
FRAMEWORKS = [  # (label, venv, agent script, runtime name the adapter reports)
    ("Pydantic AI", ".venv-pydantic", "pydantic_agent.py", "pydantic-ai"),
    ("LangGraph", ".venv-langgraph", "langgraph_agent.py", "langgraph"),
    ("Google ADK", ".venv-adk", "adk_agent.py", "google-adk"),
    ("OpenAI Agents SDK", ".venv-openai", "openai_agent.py", "openai-agents"),
    ("MS Agent Framework", ".venv-maf", "maf_agent.py", "agent-framework"),
    ("Semantic Kernel", ".venv-sk", "sk_agent.py", "semantic-kernel"),
    ("Strands Agents", ".venv-strands", "strands_agent.py", "strands-agents"),
    ("LangChain", ".venv-langchain", "langchain_agent.py", "langchain"),
    ("Claude Agent SDK", ".venv-claude", "claude_agent.py", "claude-agent-sdk"),
    ("CrewAI", ".venv-crewai", "crewai_agent.py", "crewai"),
    ("Agno", ".venv-agno", "agno_agent.py", "agno"),
    ("LlamaIndex", ".venv-llamaindex", "llamaindex_agent.py", "llamaindex"),
    ("smolagents", ".venv-smolagents", "smolagents_agent.py", "smolagents"),
]


def api(method: str, path: str, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(PRUDENCE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> {e.code}: {e.read()[:300].decode(errors='replace')}")


def register(name: str, tier: str) -> str:
    system = api("POST", "/api/registry/systems", {
        "name": name, "intendedPurpose": "Send standard letters to claims customers",
        "ownerName": "Conformance", "ownerEmail": "conformance@example.com", "riskTier": tier,
        "regulatoryRole": "DEPLOYER", "systemVersion": "1.0", "modelHash": "sha256:conformance",
        "annexI": False, "annexIII": tier == "HIGH", "operatingGeography": "European Union",
        "affectedPopulation": "claims customers", "classificationJustification": "conformance test",
        "obligations": ["Article 12 record-keeping"]})
    # A system goes to review only once its blocking compliance controls have evidence.
    controls = f"/api/registry/systems/{system['id']}/compliance-controls"
    for control in api("GET", controls):
        if not control["blocking"] or control["status"] == "SATISFIED":
            continue
        evidence = {"evidenceUri": f"https://evidence.example.com/{control['key'].lower()}",
                    "evidenceSha256": "sha256:" + "a" * 64, "evidenceVersion": "1.0",
                    "evidenceOwner": "Conformance", "signedAttestation": "conformance test"}
        if "dataset-version" in control["accepts"]:
            evidence.update({"datasetVersion": "conformance-1", "approver": "Conformance",
                             "metricsByGroup": "https://evidence.example.com/metrics"})
        api("POST", f"{controls}/{control['id']}/satisfy", evidence)
    if tier == "HIGH":
        # A high-risk system needs a trained supervisor who can stop it before review.
        api("POST", f"/api/oversight/systems/{system['id']}/assignments", {
            "role": "SUPERVISOR", "person": "Conformance supervisor", "overrideAuthority": True,
            "stopAuthority": True, "trainingEvidence": "https://evidence.example.com/training",
            "trainingExpiresAt": "2030-01-01T00:00:00Z"})
    api("POST", f"/api/registry/systems/{system['id']}/submit")
    return system["id"]


def run_agent(venv: str, script: str, env: dict) -> dict:
    proc = subprocess.run([str(HERE / venv / "bin" / "python"), "-I", str(HERE / "agents" / script)],
                          env={**os.environ, **env}, capture_output=True, text=True, timeout=180)
    for line in reversed(proc.stdout.splitlines()):
        if line.startswith("{"):
            return json.loads(line)
    return {"error": (proc.stderr.strip().splitlines() or ["no output"])[-1]}


def executions(system: str, runtime: str) -> list:
    return [e for e in api("GET", f"/api/executions?aiSystemId={system}&limit=500")
            if e.get("runtime") == runtime]


def main() -> int:
    try:
        api("GET", "/api/registry/systems")
    except Exception as e:  # noqa: BLE001
        print(f"Prudence is not answering at {PRUDENCE} ({e}). Start integrations/conformance/stack.sh first.")
        return 1
    for _, venv, _, _ in FRAMEWORKS:
        if not (HERE / venv / "bin" / "python").exists():
            print(f"missing {venv}: see README.md (one environment per framework)")
            return 1

    _, llm_url = serve()
    stamp = time.strftime("%H%M%S")
    approved = register(f"Conformance letters {stamp} (limited)", "LIMITED")
    api("POST", f"/api/registry/systems/{approved}/approve")
    pending = register(f"Conformance letters {stamp} (high, pending)", "HIGH")

    cases = [  # (case, system, pipeline, prudence url, expected decision, tool should run)
        ("ALLOW", approved, "send_customer_letter", PRUDENCE, "ALLOW", True),
        ("APPROVAL_REQUIRED", pending, "send_customer_letter", PRUDENCE, "APPROVAL_REQUIRED", False),
        ("DENY (not granted)", approved, "not_granted_pipeline", PRUDENCE, "DENY", False),
        ("PRUDENCE DOWN", approved, "send_customer_letter", "http://127.0.0.1:9", "DENY", False),
    ]
    rows, failures = [], 0
    for label, venv, script, runtime in FRAMEWORKS:
        for case, system, pipeline, url, expected, should_run in cases:
            before = len(executions(system, runtime)) if should_run else 0
            out = run_agent(venv, script, {"PRUDENCE_URL": url, "LLM_URL": llm_url,
                                           "AI_SYSTEM_ID": system, "PIPELINE": pipeline})
            if "error" in out:
                ok, detail = False, f"agent failed: {out['error'][:90]}"
            else:
                ran, answer = out["toolRan"], out["answer"] or ""
                ok = ran == should_run
                if should_run:
                    reported = len(executions(system, runtime)) > before
                    ok = ok and reported
                    detail = f"tool ran; execution reported: {'yes' if reported else 'NO'}"
                else:
                    said = expected in answer
                    ok = ok and said
                    detail = f"tool {'RAN' if ran else 'did not run'}; model told {expected}: {'yes' if said else 'NO'}"
            failures += not ok
            rows.append((label, case, "PASS" if ok else "FAIL", detail))

    widths = [max(len(r[i]) for r in rows) for i in range(3)]
    print(f"\n{'Framework':{widths[0]}}  {'Case':{widths[1]}}  {'':{widths[2]}}  Detail")
    for r in rows:
        print(f"{r[0]:{widths[0]}}  {r[1]:{widths[1]}}  {r[2]:{widths[2]}}  {r[3]}")
    print(f"\n{len(rows) - failures}/{len(rows)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
