#!/usr/bin/env python3
"""Conformance with real login: the ADK agent against Prudence secured by Keycloak.

What run.py cannot show, because the dev profile takes the caller from the request: that an
adopter can register a runtime with scripts/provision-runtime.py and be governed by its token.

OPA starts with no grant for the runtime (stack-sso.sh). This run:
1. registers the runtime `conformance-adk` with provision-runtime.py (Keycloak client,
   preferred_username mapper, OPA grant written to .sso/policy/data.json);
2. registers two AI systems as the test user alice (one approved, one high-risk awaiting
   approval);
3. runs the ADK agent with the runtime's client credentials. An ALLOW can only come from the
   token: OPA grants nothing to the name the adapter sends in the body.

Cases: ALLOW (tool runs, execution recorded under the token's identity), APPROVAL_REQUIRED,
DENY (pipeline not granted), wrong client secret (no token: fail closed), and a direct call
with no token (401, nothing decided).

Needs scripts/keycloak.sh and stack-sso.sh running, and .venv-adk (README.md). Uses the dev
realm's test credentials only (admin/admin, alice/alice). Exits 1 if any case fails.
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
RUNTIME = "conformance-adk"
CLIENT_ID = f"{RUNTIME}-pdp"
SECRET_FILE = STATE / f"{CLIENT_ID}.secret"


def token(**form) -> str:
    body = urllib.parse.urlencode(form).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=body), timeout=10) as r:
        return json.load(r)["access_token"]


def call(method: str, path: str, bearer: str | None, body=None) -> tuple[int, object]:
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    req = urllib.request.Request(PRUDENCE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:300].decode(errors="replace")


def api(method: str, path: str, bearer: str, body=None):
    status, out = call(method, path, bearer, body)
    if status >= 300:
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


def run_agent(env: dict) -> dict:
    proc = subprocess.run([str(HERE / ".venv-adk" / "bin" / "python"), "-I", str(HERE / "agents" / "adk_agent.py")],
                          env={**os.environ, **env}, capture_output=True, text=True, timeout=180)
    for line in reversed(proc.stdout.splitlines()):
        if line.startswith("{"):
            return json.loads(line)
    return {"error": (proc.stderr.strip().splitlines() or ["no output"])[-1]}


def main() -> int:
    if call("GET", "/api/registry/systems", None)[0] != 401:
        print(f"Expected the secured Prudence on {PRUDENCE} (start stack-sso.sh).")
        return 1

    # 1. Register the runtime, as an adopter would.
    subprocess.run([sys.executable, "-I", str(REPO / "scripts" / "provision-runtime.py"),
                    "--name", RUNTIME, "--pipeline", "send_customer_letter",
                    "--keycloak-url", KEYCLOAK, "--realm", REALM,
                    "--opa-data", str(STATE / "policy" / "data.json"), "--secret-file", str(SECRET_FILE)],
                   env={**os.environ, "KEYCLOAK_ADMIN_PASSWORD": "admin"}, check=True)
    time.sleep(2)  # OPA watches the policy folder

    # 2. The AI systems, registered and reviewed by a human with read-write.
    alice = token(grant_type="password", client_id="prudence-spa", username="alice", password="alice")
    stamp = time.strftime("%H%M%S")
    approved = register(alice, f"Conformance SSO {stamp} (limited)", "LIMITED")
    api("POST", f"/api/registry/systems/{approved}/approve", alice)
    pending = register(alice, f"Conformance SSO {stamp} (high, pending)", "HIGH")

    _, llm_url = serve()
    secret = SECRET_FILE.read_text().strip()
    m2m = {"PRUDENCE_URL": PRUDENCE, "LLM_URL": llm_url, "PRUDENCE_PDP_TOKEN_URL": TOKEN_URL,
           "PRUDENCE_PDP_CLIENT_ID": CLIENT_ID, "PRUDENCE_PDP_CLIENT_SECRET": secret}
    subject = f"service-account-{CLIENT_ID}"

    rows = []

    def check(case: str, ok: bool, detail: str):
        rows.append((case, "PASS" if ok else "FAIL", detail))

    # The identity Prudence will see: the token's preferred_username.
    claims = token(grant_type="client_credentials", client_id=CLIENT_ID, client_secret=secret, scope="pdp.decide")
    part = claims.split(".")[1]
    payload = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    check("token names the runtime", payload.get("preferred_username") == subject,
          f"preferred_username={payload.get('preferred_username')}, scope={payload.get('scope')}")

    out = run_agent({**m2m, "AI_SYSTEM_ID": approved, "PIPELINE": "send_customer_letter"})
    reports = api("GET", f"/api/executions?aiSystemId={approved}", alice)
    mine = [r for r in reports if r.get("runtime") == "google-adk"]
    check("ALLOW", out.get("toolRan") is True and bool(mine) and mine[0].get("decisionStatus") == "ALLOW",
          f"tool ran: {out.get('toolRan')}; execution recorded with decision {mine[0].get('decisionStatus') if mine else 'NONE'}"
          + (f"; {out['error'][:80]}" if "error" in out else ""))

    out = run_agent({**m2m, "AI_SYSTEM_ID": pending, "PIPELINE": "send_customer_letter"})
    check("APPROVAL_REQUIRED", out.get("toolRan") is False and "APPROVAL_REQUIRED" in (out.get("answer") or ""),
          f"tool ran: {out.get('toolRan')}; answer mentions APPROVAL_REQUIRED")

    out = run_agent({**m2m, "AI_SYSTEM_ID": approved, "PIPELINE": "not_granted_pipeline"})
    check("DENY (not granted)", out.get("toolRan") is False and "DENY" in (out.get("answer") or ""),
          f"tool ran: {out.get('toolRan')}; answer mentions DENY")

    out = run_agent({**m2m, "PRUDENCE_PDP_CLIENT_SECRET": "wrong", "AI_SYSTEM_ID": approved,
                     "PIPELINE": "send_customer_letter"})
    check("wrong client secret", out.get("toolRan") is False,
          f"tool ran: {out.get('toolRan')} (no token, so the gate fails closed)")

    status, _ = call("POST", "/api/policy/decisions", None,
                     {"subject": subject, "pipeline": "send_customer_letter", "aiSystemId": approved, "action": "run"})
    check("no token at all", status == 401, f"Prudence answered {status}")

    status, _ = call("POST", "/api/policy/decisions", alice,
                     {"subject": subject, "pipeline": "send_customer_letter", "aiSystemId": approved, "action": "run"})
    check("a person's token", status == 403, f"Prudence answered {status} (only workloads with pdp.decide may ask)")

    width = max(len(r[0]) for r in rows)
    print()
    for case, verdict, detail in rows:
        print(f"{case:{width}}  {verdict}  {detail}")
    failed = sum(r[1] == "FAIL" for r in rows)
    print(f"\n{len(rows) - failed}/{len(rows)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
