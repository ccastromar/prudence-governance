#!/usr/bin/env bash
# Prints the AOS environment for the live Prudence-governed demo: one AI-system id per risk
# case, resolved BY NAME through Prudence's API from the public MVP seed (scripts/seed-mvp.sh).
# Read-only: it never creates or changes registry data. It also prints, on stderr, each
# system's current state and the verdict AOS should get first, so you can check before going live.
#
#   ./scripts/aos-demo-env.sh                       # dev profile (no login) or local SSO (signs in as alice)
#   ./scripts/aos-demo-env.sh >> ../aos-agentic-orchestration-system/.env
#   PRUDENCE_URL=https://prudence.example PRUDENCE_TOKEN=... ./scripts/aos-demo-env.sh
#
# Guide: docs/functional/06-demo-aos-en-vivo.md
set -euo pipefail
API="${PRUDENCE_URL:-http://127.0.0.1:8099}/api/registry/systems"
ISSUER="${OIDC_ISSUER:-http://localhost:8081/realms/chestertown}"
SEED_USER="${SEED_USER:-alice}"
TOKEN="${PRUDENCE_TOKEN:-}"

if [ -z "$TOKEN" ]; then
  code="$(curl -s -o /dev/null -w '%{http_code}' "$API" || true)"
  case "$code" in
    200) ;;
    401)
      TOKEN="$(curl -sf "$ISSUER/protocol/openid-connect/token" -d grant_type=password -d client_id=prudence-spa \
        -d username="$SEED_USER" -d password="$SEED_USER" \
        | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')" || {
        echo "could not sign in as $SEED_USER at $ISSUER; set PRUDENCE_TOKEN instead" >&2; exit 1; }
      ;;
    000) echo "Prudence is not reachable at $API" >&2; exit 1 ;;
    *) echo "unexpected HTTP $code from $API" >&2; exit 1 ;;
  esac
fi

API="$API" TOKEN="$TOKEN" python3 -I - <<'PY'
import json, os, sys, urllib.request

# (env var read by AOS, seeded system name, demo case)
CASES = [
    ("AOS_PRUDENCE_DEMO_SYSTEM_ID", "MVP · Asistente de soporte al cliente", "support lookup"),
    ("AOS_PRUDENCE_CREDIT_SYSTEM_ID", "MVP · Preselección de crédito al consumo", "credit pre-screening"),
    ("AOS_PRUDENCE_SOCIAL_SCORING_SYSTEM_ID", "MVP · Puntuación social de ciudadanos", "social scoring"),
    ("AOS_PRUDENCE_TRANSLATOR_SYSTEM_ID", "MVP · Traductor de comunicaciones", "translator"),
]

headers = {"Accept": "application/json"}
if os.environ["TOKEN"]:
    headers["Authorization"] = "Bearer " + os.environ["TOKEN"]
with urllib.request.urlopen(urllib.request.Request(os.environ["API"], headers=headers)) as r:
    systems = {s["name"]: s for s in json.load(r)}

def first_verdict(s):
    # Mirrors PolicyDecisionService's lifecycle half; an APPROVED system still needs OPA to
    # grant the pipeline (policy/opa/data.json), so ALLOW is only the expected outcome.
    if s["riskTier"] == "PROHIBITED": return "DENY"
    if s["approvalStatus"] == "APPROVED": return "ALLOW (if OPA grants the pipeline)"
    if s["riskTier"] == "HIGH": return "APPROVAL_REQUIRED"
    return "DENY"

missing = [name for _, name, _ in CASES if name not in systems]
if missing:
    print("missing systems (run scripts/seed-mvp.sh first): " + ", ".join(missing), file=sys.stderr)
    sys.exit(1)

print(f"{'case':22} {'tier':10} {'status':24} first verdict", file=sys.stderr)
for var, name, case in CASES:
    s = systems[name]
    print(f"{case:22} {s['riskTier']:10} {s['approvalStatus']:24} {first_verdict(s)}", file=sys.stderr)
    print(f"{var}={s['id']}")
PY
