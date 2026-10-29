#!/usr/bin/env python3
"""Conformance with real login: thirteen frameworks, an MCP gateway and Ernest, against Prudence
secured by Keycloak.

What run.py cannot show, because the dev profile takes the caller from the request: that an
adopter can register a runtime with scripts/provision-runtime.py and be governed by its token,
and that the whole suite (decision, execution, evidence) tells one story.

OPA starts with no grant for any runtime (stack-sso.sh). This run:
1. registers one runtime per framework, plus one MCP gateway, with provision-runtime.py
   (Keycloak client, preferred_username mapper, OPA grant written to .sso/policy/data.json);
2. registers AI systems as the test user alice (approved ones, and a high-risk one awaiting
   approval);
3. runs each framework's agent on its own runtime's client credentials. An ALLOW can only come
   from the token: OPA grants nothing to the name an adapter sends in the body;
4. asks AuthZEN about MCP tool calls with the gateway's token;
5. with Ernest in the stack: checks the decisions and executions were sealed there, and that
   Prudence's execution-source check flags a runtime that reported without evidence.

Needs scripts/keycloak.sh and stack-sso.sh running, and the per-framework environments (README.md).
Uses the dev realm's test credentials only (admin/admin, alice/alice). Exits 1 if any case fails.
"""
from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from fake_llm import serve  # noqa: E402

REPO = HERE.parent.parent
PRUDENCE = "http://127.0.0.1:18099"
KEYCLOAK = os.getenv("KEYCLOAK_URL", "http://localhost:8081")
REALM = "chestertown"
TOKEN_URL = f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token"
STATE = HERE / ".sso"
FRAMEWORKS = [  # (label, runtime to register, venv, agent script, runtime name the adapter reports)
    ("Pydantic AI", "conformance-pydantic", ".venv-pydantic", "pydantic_agent.py", "pydantic-ai"),
    ("LangGraph", "conformance-langgraph", ".venv-langgraph", "langgraph_agent.py", "langgraph"),
    ("Google ADK", "conformance-adk", ".venv-adk", "adk_agent.py", "google-adk"),
    ("OpenAI Agents SDK", "conformance-openai", ".venv-openai", "openai_agent.py", "openai-agents"),
    ("MS Agent Framework", "conformance-maf", ".venv-maf", "maf_agent.py", "agent-framework"),
    ("Semantic Kernel", "conformance-sk", ".venv-sk", "sk_agent.py", "semantic-kernel"),
    ("Strands Agents", "conformance-strands", ".venv-strands", "strands_agent.py", "strands-agents"),
    ("LangChain", "conformance-langchain", ".venv-langchain", "langchain_agent.py", "langchain"),
    ("Claude Agent SDK", "conformance-claude", ".venv-claude", "claude_agent.py", "claude-agent-sdk"),
    ("CrewAI", "conformance-crewai", ".venv-crewai", "crewai_agent.py", "crewai"),
    ("Agno", "conformance-agno", ".venv-agno", "agno_agent.py", "agno"),
    ("LlamaIndex", "conformance-llamaindex", ".venv-llamaindex", "llamaindex_agent.py", "llamaindex"),
    ("smolagents", "conformance-smolagents", ".venv-smolagents", "smolagents_agent.py", "smolagents"),
]
MCP_RUNTIME = "conformance-mcp"


def token(**form) -> str:
    body = urllib.parse.urlencode(form).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=body), timeout=10) as r:
        return json.load(r)["access_token"]


def claims(jwt: str) -> dict:
    part = jwt.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def call(method: str, url: str, bearer: str | None = None, body=None) -> tuple[int, object]:
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    req = urllib.request.Request(url if url.startswith("http") else PRUDENCE + url,
                                 data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw[:300].decode(errors="replace")
    except urllib.error.URLError as e:
        return 0, str(e.reason)


def api(method: str, path: str, bearer: str, body=None):
    status, out = call(method, path, bearer, body)
    if not 200 <= status < 300:
        raise SystemExit(f"{method} {path} -> {status}: {out}")
    return out


def register(user: str, name: str, tier: str) -> str:
    system = api("POST", "/api/registry/systems", user, {
        "name": name, "intendedPurpose": "Send standard letters to claims customers",
        "ownerName": "Conformance", "ownerEmail": "conformance@example.com", "riskTier": tier,
        "regulatoryRole": "DEPLOYER", "systemVersion": "1.0", "modelHash": "sha256:conformance",
        "annexI": False, "annexIII": tier == "HIGH", "operatingGeography": "European Union",
        "affectedPopulation": "claims customers", "classificationJustification": "conformance test",
        "obligations": ["Article 12 record-keeping"]})
    controls = f"/api/registry/systems/{system['id']}/compliance-controls"
    for control in api("GET", controls, user):
        if not control["blocking"] or control["status"] == "SATISFIED":
            continue
        evidence = {"evidenceUri": f"https://evidence.example.com/{control['key'].lower()}",
                    "evidenceSha256": "sha256:" + "a" * 64, "evidenceVersion": "1.0",
                    "evidenceOwner": "Conformance", "signedAttestation": "conformance test"}
        if "dataset-version" in control["accepts"]:
            evidence.update({"datasetVersion": "conformance-1", "approver": "Conformance",
                             "metricsByGroup": "https://evidence.example.com/metrics"})
        api("POST", f"{controls}/{control['id']}/satisfy", user, evidence)
    if tier == "HIGH":
        api("POST", f"/api/oversight/systems/{system['id']}/assignments", user, {
            "role": "SUPERVISOR", "person": "Conformance supervisor", "overrideAuthority": True,
            "stopAuthority": True, "trainingEvidence": "https://evidence.example.com/training",
            "trainingExpiresAt": "2030-01-01T00:00:00Z"})
    api("POST", f"/api/registry/systems/{system['id']}/submit", user)
    return system["id"]


def provision(runtime: str, pipeline: str) -> str:
    """Register the runtime as an adopter would; returns its client secret."""
    secret_file = STATE / f"{runtime}-pdp.secret"
    proc = subprocess.run(
        [sys.executable, "-I", str(REPO / "scripts" / "provision-runtime.py"), "--name", runtime,
         "--pipeline", pipeline, "--keycloak-url", KEYCLOAK, "--realm", REALM,
         "--opa-data", str(STATE / "policy" / "data.json"), "--secret-file", str(secret_file)],
        env={**os.environ, "KEYCLOAK_ADMIN_PASSWORD": "admin"}, capture_output=True, text=True)
    if proc.returncode:
        raise SystemExit(f"provision-runtime.py {runtime} failed:\n{proc.stderr or proc.stdout}")
    return secret_file.read_text().strip()


def run_agent(venv: str, script: str, env: dict) -> dict:
    proc = subprocess.run([str(HERE / venv / "bin" / "python"), "-I", str(HERE / "agents" / script)],
                          env={**os.environ, **env}, capture_output=True, text=True, timeout=180)
    for line in reversed(proc.stdout.splitlines()):
        if line.startswith("{"):
            return json.loads(line)
    return {"error": (proc.stderr.strip().splitlines() or ["no output"])[-1]}


def main() -> int:
    if call("GET", "/api/registry/systems")[0] != 401:
        print(f"Expected the secured Prudence on {PRUDENCE} (start stack-sso.sh).")
        return 1
    ernest = (STATE / "ernest-url").read_text().strip() if (STATE / "ernest-url").exists() else ""
    with_ernest = bool(ernest) and call("GET", f"{ernest}/api/stats")[0] == 200
    for _, _, venv, _, _ in FRAMEWORKS:
        if not (HERE / venv / "bin" / "python").exists():
            print(f"missing {venv}: see README.md (one environment per framework)")
            return 1

    rows: list[tuple[str, str, str, str]] = []

    def check(group: str, case: str, ok: bool, detail: str):
        rows.append((group, case, "PASS" if ok else "FAIL", detail))

    # 1. Runtimes, registered as adopters would. OPA reloads the folder it watches.
    secrets = {runtime: provision(runtime, "send_customer_letter") for _, runtime, _, _, _ in FRAMEWORKS}
    mcp_secret = provision(MCP_RUNTIME, "tool:send_customer_letter")
    time.sleep(2)

    # 2. AI systems, registered and reviewed by a person with read-write.
    alice = token(grant_type="password", client_id="prudence-spa", username="alice", password="alice")
    stamp = time.strftime("%H%M%S")
    approved = register(alice, f"Conformance SSO {stamp} (limited)", "LIMITED")
    api("POST", f"/api/registry/systems/{approved}/approve", alice)
    unsealed = register(alice, f"Conformance SSO {stamp} (no evidence)", "LIMITED")
    api("POST", f"/api/registry/systems/{unsealed}/approve", alice)
    pending = register(alice, f"Conformance SSO {stamp} (high, pending)", "HIGH")

    # 3. Each framework on its own runtime's credentials.
    _, llm_url = serve()
    for label, runtime, venv, script, reported in FRAMEWORKS:
        client_id, secret = f"{runtime}-pdp", secrets[runtime]
        subject = f"service-account-{client_id}"
        env = {"PRUDENCE_URL": PRUDENCE, "LLM_URL": llm_url, "PRUDENCE_PDP_TOKEN_URL": TOKEN_URL,
               "PRUDENCE_PDP_CLIENT_ID": client_id, "PRUDENCE_PDP_CLIENT_SECRET": secret,
               "ERNEST_URL": ernest if with_ernest else ""}

        c = claims(token(grant_type="client_credentials", client_id=client_id, client_secret=secret))
        check(label, "token names the runtime", c.get("preferred_username") == subject,
              f"preferred_username={c.get('preferred_username')}, scope={c.get('scope')}")

        out = run_agent(venv, script, {**env, "AI_SYSTEM_ID": approved, "PIPELINE": "send_customer_letter"})
        mine = [r for r in api("GET", f"/api/executions?aiSystemId={approved}", alice) if r.get("runtime") == reported]
        check(label, "ALLOW", out.get("toolRan") is True and bool(mine) and mine[0].get("decisionStatus") == "ALLOW",
              f"tool ran: {out.get('toolRan')}; execution recorded with decision "
              f"{mine[0].get('decisionStatus') if mine else 'NONE'}" + (f"; {out['error'][:80]}" if "error" in out else ""))

        out = run_agent(venv, script, {**env, "AI_SYSTEM_ID": pending, "PIPELINE": "send_customer_letter"})
        check(label, "APPROVAL_REQUIRED", out.get("toolRan") is False and "APPROVAL_REQUIRED" in (out.get("answer") or ""),
              f"tool ran: {out.get('toolRan')}; the model was told APPROVAL_REQUIRED")

        out = run_agent(venv, script, {**env, "AI_SYSTEM_ID": approved, "PIPELINE": "not_granted_pipeline"})
        check(label, "DENY (not granted)", out.get("toolRan") is False and "DENY" in (out.get("answer") or ""),
              f"tool ran: {out.get('toolRan')}; the model was told DENY")

        out = run_agent(venv, script, {**env, "PRUDENCE_PDP_CLIENT_SECRET": "wrong",
                                       "AI_SYSTEM_ID": approved, "PIPELINE": "send_customer_letter"})
        check(label, "wrong client secret", out.get("toolRan") is False,
              f"tool ran: {out.get('toolRan')} (no token, so the gate fails closed)")

    # The negative control for step 5: one allowed run that reports to Prudence, seals nothing.
    label, runtime, venv, script, _ = next(f for f in FRAMEWORKS if f[4] == "agent-framework")
    run_agent(venv, script, {"PRUDENCE_URL": PRUDENCE, "LLM_URL": llm_url, "PRUDENCE_PDP_TOKEN_URL": TOKEN_URL,
                             "PRUDENCE_PDP_CLIENT_ID": f"{runtime}-pdp", "PRUDENCE_PDP_CLIENT_SECRET": secrets[runtime],
                             "ERNEST_URL": "", "AI_SYSTEM_ID": unsealed, "PIPELINE": "send_customer_letter"})

    # The front door itself.
    body = {"subject": "anyone", "pipeline": "send_customer_letter", "aiSystemId": approved, "action": "run"}
    check("Prudence", "no token at all", call("POST", "/api/policy/decisions", None, body)[0] == 401,
          f"answered {call('POST', '/api/policy/decisions', None, body)[0]}")
    status = call("POST", "/api/policy/decisions", alice, body)[0]
    check("Prudence", "a person's token", status == 403, f"answered {status} (only workloads with pdp.decide may ask)")

    # 4. An MCP gateway asking about tool calls over AuthZEN, with its own token.
    gateway = token(grant_type="client_credentials", client_id=f"{MCP_RUNTIME}-pdp", client_secret=mcp_secret)

    def evaluate(resource: dict) -> tuple[int, object]:
        return call("POST", "/access/v1/evaluation", gateway,
                    {"subject": {"type": "agent", "id": "claims-assistant"}, "resource": resource,
                     "action": {"name": "run"}})

    status, out = evaluate({"type": "tool", "id": "send_customer_letter", "properties": {"aiSystemId": approved}})
    ctx = out.get("context", {}) if isinstance(out, dict) else {}
    check("MCP gateway", "granted tool, approved system", status == 200 and out.get("decision") is True,
          f"{status}, decision={out.get('decision') if isinstance(out, dict) else out}, Prudence {ctx.get('prudenceDecision')}")
    status, out = evaluate({"type": "tool", "id": "send_customer_letter", "properties": {"aiSystemId": pending}})
    ctx = out.get("context", {}) if isinstance(out, dict) else {}
    check("MCP gateway", "system awaiting approval", status == 200 and out.get("decision") is False and ctx.get("approvalRequired"),
          f"{status}, decision={out.get('decision') if isinstance(out, dict) else out}, approver {ctx.get('approver')}")
    status, out = evaluate({"type": "tool", "id": "delete_customer", "properties": {"aiSystemId": approved}})
    check("MCP gateway", "tool not granted", status == 200 and isinstance(out, dict) and out.get("decision") is False,
          f"{status}, decision={out.get('decision') if isinstance(out, dict) else out}")
    status, out = evaluate({"type": "tool", "id": "send_customer_letter"})
    check("MCP gateway", "tool mapped to no AI system", status == 400,
          f"{status}: {(out.get('error') or {}).get('message', '')[:70] if isinstance(out, dict) else out}")

    # 5. Ernest: the same story in the evidence.
    if with_ernest:
        time.sleep(25)  # the outbox (2 s), Ernest's batching, and Prudence's 20 s source grace
        _, stats = call("GET", f"{ernest}/api/stats/executions")
        counts = (stats or {}).get("counts", {}) if isinstance(stats, dict) else {}
        check("Ernest", "executions sealed per system", counts.get(approved, 0) >= len(FRAMEWORKS),
              f"{counts.get(approved, 0)} execution(s) for the approved system (one per framework expected)")
        _, prov = call("GET", f"{ernest}/api/provenances?modelId={approved}")
        history = prov.get("history", []) if isinstance(prov, dict) else []
        # Prudence's direct transport records decisions as inferences with stage
        # governance_decision; the ingestor and Kafka transports as policy_decision events.
        decisions = [h for h in history if h.get("type") == "policy_decision"
                     or (h.get("metadata") or {}).get("stage") == "governance_decision"]
        check("Ernest", "decisions sealed", len(decisions) >= len(FRAMEWORKS),
              f"{len(decisions)} policy decision(s) recorded for the approved system")
        _, sources = call("GET", "/api/posture/execution-sources", alice)
        mismatches = sources.get("mismatches", []) if isinstance(sources, dict) else []
        flagged = {m.get("aiSystemId"): m.get("kind") for m in mismatches}
        check("Prudence × Ernest", "sources agree when both are fed", approved not in flagged,
              "no mismatch for the approved system" if approved not in flagged else f"flagged {flagged[approved]}")
        check("Prudence × Ernest", "report without evidence is flagged",
              flagged.get(unsealed) == "REPORTED_NOT_EVIDENCED", f"run without Ernest: {flagged.get(unsealed, 'not flagged')}")
    else:
        check("Ernest", "skipped", True, "stack-sso.sh ran with ERNEST=off")

    widths = [max(len(r[i]) for r in rows) for i in range(3)]
    print()
    for r in rows:
        print(f"{r[0]:{widths[0]}}  {r[1]:{widths[1]}}  {r[2]:{widths[2]}}  {r[3]}")
    failed = sum(r[2] == "FAIL" for r in rows)
    print(f"\n{len(rows) - failed}/{len(rows)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
