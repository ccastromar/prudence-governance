#!/usr/bin/env bash
# Re-arms the four public-MVP systems of the AOS live demo to their starting verdicts, so the
# demo can be replayed without restoring databases. It uses only the normal governed
# lifecycle — Prudence has no "un-approve" and its audit trail is append-only:
#
#   an approved (or half-approved) system gets a NOT_SERIOUS incident with causal link
#   RULED_OUT that suspends it, closed at once with a note saying it was a demo reset;
#   the high-risk credit system is then resubmitted, so it waits for two sign-offs again.
#
# Every reset therefore leaves a closed incident and review events behind, on purpose.
# It needs one read-write identity (no reviewer): dev profile, local SSO (signs in as alice)
# or PRUDENCE_TOKEN. Ids, AOS and its .env are untouched.
#
#   ./scripts/aos-demo-rearm.sh
#   PRUDENCE_URL=https://prudence.example PRUDENCE_TOKEN=... ./scripts/aos-demo-rearm.sh
#
# Guide: docs/functional/06-demo-aos-en-vivo.md
set -euo pipefail
BASE="${PRUDENCE_URL:-http://127.0.0.1:8099}"
ISSUER="${OIDC_ISSUER:-http://localhost:8081/realms/chestertown}"
SEED_USER="${SEED_USER:-alice}"
TOKEN="${PRUDENCE_TOKEN:-}"

if [ -z "$TOKEN" ]; then
  code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/registry/systems" || true)"
  case "$code" in
    200) ;;
    401)
      TOKEN="$(curl -sf "$ISSUER/protocol/openid-connect/token" -d grant_type=password -d client_id=prudence-spa \
        -d username="$SEED_USER" -d password="$SEED_USER" \
        | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')" || {
        echo "could not sign in as $SEED_USER at $ISSUER; set PRUDENCE_TOKEN instead" >&2; exit 1; }
      ;;
    000) echo "Prudence is not reachable at $BASE" >&2; exit 1 ;;
    *) echo "unexpected HTTP $code from $BASE/api/registry/systems" >&2; exit 1 ;;
  esac
fi

BASE="$BASE" TOKEN="$TOKEN" python3 -I - <<'PY'
import json, os, sys, urllib.error, urllib.request
from datetime import datetime, timezone

base = os.environ["BASE"].rstrip("/")
systems_api, incidents_api = f"{base}/api/registry/systems", f"{base}/api/incidents"

def call(method, url, body=None):
    headers = {"Accept": "application/json"}
    if os.environ["TOKEN"]:
        headers["Authorization"] = "Bearer " + os.environ["TOKEN"]
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method=method, headers=headers, data=data)) as r:
            raw = r.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} {method} {url}: {e.read().decode(errors='replace')[:300]}")

SUPPORT = "MVP · Asistente de soporte al cliente"
CREDIT = "MVP · Preselección de crédito al consumo"
SOCIAL = "MVP · Puntuación social de ciudadanos"
TRANSLATOR = "MVP · Traductor de comunicaciones"

systems = {s["name"]: s for s in call("GET", systems_api)}
missing = [n for n in (SUPPORT, CREDIT, SOCIAL, TRANSLATOR) if n not in systems]
if missing:
    raise SystemExit("missing systems (run scripts/seed-mvp.sh first): " + ", ".join(missing))

def refresh(s): return call("GET", f"{systems_api}/{s['id']}")

def suspend_for_reset(s):
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    incident = call("POST", incidents_api, {
        "aiSystemId": s["id"],
        "title": "Reinicio de la demo en vivo de AOS",
        "description": "No es un incidente real: se suspende el sistema para devolver la demo de AOS a su estado inicial.",
        "occurredAt": now, "detectedAt": now, "memberStates": ["ES"],
        "category": "NOT_SERIOUS", "widespreadInfringement": False,
        "causalLink": "RULED_OUT", "suspendSystem": True,
    })
    call("POST", f"{incidents_api}/{incident['id']}/close",
         {"note": "Reinicio de la demo de AOS: sin daño ni vínculo causal con la IA."})
    print(f"    → suspendido por reinicio de demo (incidente {incident['id']} cerrado)")
    return refresh(s)

# Starting verdicts: support and translator DENY (not approved, can be submitted); credit
# APPROVAL_REQUIRED waiting for its first sign-off; social scoring always DENY.
HALF_OR_APPROVED = {"APPROVED", "PENDING_APPROVAL", "PENDING_SECOND_APPROVAL"}
for name in (SUPPORT, TRANSLATOR):
    s = systems[name]
    print(f"= {name}: {s['approvalStatus']}")
    if s["approvalStatus"] in HALF_OR_APPROVED:
        s = suspend_for_reset(s)
    print(f"    ✓ {s['approvalStatus']}")

s = systems[CREDIT]
print(f"= {CREDIT}: {s['approvalStatus']}")
if s["approvalStatus"] in {"APPROVED", "PENDING_SECOND_APPROVAL"}:
    s = suspend_for_reset(s)
if s["approvalStatus"] in {"UNDER_ASSESSMENT", "REJECTED", "EXPIRED", "SUSPENDED"}:
    s = call("POST", f"{systems_api}/{s['id']}/submit")
    print("    → enviada de nuevo a revisión")
if s["approvalStatus"] != "PENDING_APPROVAL":
    raise SystemExit(f"{CREDIT}: quedó en {s['approvalStatus']}, se esperaba PENDING_APPROVAL")
print("    ✓ PENDING_APPROVAL")

s = systems[SOCIAL]
print(f"= {SOCIAL}: {s['approvalStatus']}")
if s["approvalStatus"] != "BLOCKED":
    raise SystemExit(f"{SOCIAL}: está en {s['approvalStatus']}, se esperaba BLOCKED")
print("    ✓ BLOCKED")
PY
