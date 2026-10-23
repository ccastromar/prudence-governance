#!/usr/bin/env bash
# Idempotent public-MVP seed. It uses the API, normal validation and normal audit trail.
# SSO mode requires three different user tokens (an author and two system approvers):
#   SEED_AUTHOR_TOKEN, SEED_REVIEWER_ONE_TOKEN, SEED_REVIEWER_TWO_TOKEN
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PRUDENCE_URL="${PRUDENCE_URL:-http://127.0.0.1:8099}"
export SEED_AUTHOR_TOKEN="${SEED_AUTHOR_TOKEN:-}"
export SEED_REVIEWER_ONE_TOKEN="${SEED_REVIEWER_ONE_TOKEN:-}"
export SEED_REVIEWER_TWO_TOKEN="${SEED_REVIEWER_TWO_TOKEN:-}"

python3 -I - "$HERE/seed-mvp-systems.json" <<'PY'
import json, os, sys, urllib.error, urllib.request
from datetime import datetime, timezone

base = os.environ["PRUDENCE_URL"].rstrip("/")
systems_api, incidents_api = f"{base}/api/registry/systems", f"{base}/api/incidents"
tokens = {"author": os.environ["SEED_AUTHOR_TOKEN"], "reviewer_one": os.environ["SEED_REVIEWER_ONE_TOKEN"], "reviewer_two": os.environ["SEED_REVIEWER_TWO_TOKEN"]}

def request(method, url, actor=None, body=None):
    headers = {"Accept": "application/json"}
    if actor and tokens[actor]: headers["Authorization"] = f"Bearer {tokens[actor]}"
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"; data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method=method, headers=headers, data=data)) as response:
            raw = response.read(); return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"HTTP {error.code} {method} {url}: {error.read().decode(errors='replace')[:500]}") from error

missing = [name for name, token in tokens.items() if not token]
if missing:
    raise SystemExit("Faltan tokens de identidades independientes: " + ", ".join(missing))
try:
    existing = {s["name"]: s for s in request("GET", systems_api, "author")}
except RuntimeError as error:
    raise SystemExit(str(error))

def refresh(s): return request("GET", f"{systems_api}/{s['id']}", "author")
def post(s, suffix, actor, body=None): return request("POST", f"{systems_api}/{s['id']}{suffix}", actor, body)

def ensure_review_ready(s):
    """Add only the demo evidence required to exercise an actual review flow."""
    if s["riskTier"] != "HIGH": return
    assignments_url = f"{base}/api/oversight/systems/{s['id']}/assignments"
    assignments = request("GET", assignments_url, "author")
    now = datetime.now(timezone.utc)
    has_supervisor = any(
        a.get("role") == "SUPERVISOR"
        and (a.get("overrideAuthority") or a.get("stopAuthority"))
        and a.get("trainingExpiresAt", "") > now.isoformat().replace("+00:00", "Z")
        for a in assignments
    )
    if not has_supervisor:
        request("POST", assignments_url, "author", {
            "role": "SUPERVISOR",
            "person": "MVP governance supervisor",
            "overrideAuthority": True,
            "stopAuthority": True,
            "trainingEvidence": "https://evidence.example.eu/mvp/supervisor-training",
            "trainingExpiresAt": "2030-01-01T00:00:00Z",
        })

    controls_url = f"{systems_api}/{s['id']}/compliance-controls"
    for control in request("GET", controls_url, "author"):
        if not control["blocking"] or control["status"] == "SATISFIED": continue
        evidence = {
            "evidenceUri": f"https://evidence.example.eu/mvp/{s['id']}/{control['key'].lower()}",
            "evidenceSha256": "sha256:" + "a" * 64,
            "evidenceVersion": s["systemVersion"],
            "evidenceOwner": "MVP Governance Demo",
            "signedAttestation": "MVP demo attestation",
        }
        if "dataset-version" in control["accepts"]:
            evidence.update({
                "datasetVersion": "mvp-dataset-2026-10",
                "metricsByGroup": "https://evidence.example.eu/mvp/metrics-by-group",
                "approver": "MVP governance supervisor",
            })
        request("POST", f"{controls_url}/{control['id']}/satisfy", "author", evidence)

def submit(s):
    if s["approvalStatus"] in {"UNDER_ASSESSMENT", "REJECTED", "EXPIRED", "SUSPENDED"}:
        ensure_review_ready(s)
        print("    → enviada a revisión"); return post(s, "/submit", "author")
    return s
def approve_one(s):
    if s["approvalStatus"] == "PENDING_APPROVAL":
        print("    → primera aprobación"); return post(s, "/approve", "reviewer_one")
    return s
def approve_two(s):
    if s["approvalStatus"] == "PENDING_SECOND_APPROVAL":
        print("    → segunda aprobación independiente"); return post(s, "/approve", "reviewer_two")
    return s
def suspend(s):
    if s["approvalStatus"] != "APPROVED": return s
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    print("    → incidente abierto; sistema suspendido")
    request("POST", incidents_api, "author", {"aiSystemId":s["id"],"title":"Incidente demostrativo del seed MVP","description":"Incidente de ejemplo para mostrar la suspensión inmediata del sistema.","occurredAt":now,"detectedAt":now,"memberStates":["ES"],"category":"NOT_SERIOUS","widespreadInfringement":False,"causalLink":"SUSPECTED","suspendSystem":True})
    return refresh(s)

for specimen in json.load(open(sys.argv[1], encoding="utf-8")):
    target = specimen.pop("targetStatus"); name = specimen["name"]; system = existing.get(name)
    if system is None:
        system = request("POST", systems_api, "author", specimen); print(f"+ {name}: creado ({system['approvalStatus']})")
    else: print(f"= {name}: existente ({system['approvalStatus']})")
    if target == "PENDING_APPROVAL": system = submit(system)
    elif target == "PENDING_SECOND_APPROVAL": system = approve_one(submit(system))
    elif target == "APPROVED": system = approve_two(approve_one(submit(system)))
    elif target == "REJECTED":
        system = submit(system)
        if system["approvalStatus"] in {"PENDING_APPROVAL", "PENDING_SECOND_APPROVAL"}:
            print("    → rechazada por revisión"); system = post(system, "/reject", "reviewer_one", {"reason":"Seed MVP: falta evidencia de calidad y sesgo."})
    elif target == "SUSPENDED": system = suspend(approve_two(approve_one(submit(system))))
    elif target not in {"UNDER_ASSESSMENT", "BLOCKED"}: raise SystemExit(f"Estado objetivo no soportado: {target}")
    system = refresh(system)
    if system["approvalStatus"] != target: raise SystemExit(f"{name}: quedó en {system['approvalStatus']}, se esperaba {target}")
    print(f"    ✓ {target}")
PY
