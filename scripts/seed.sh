#!/usr/bin/env bash
# Registers the sample AI systems from scripts/seed-systems.json (the ones docs/functional/ walks
# through) through Prudence's API — the same validation and audit as a person using the
# console, no SQL behind its back. Idempotent: a system whose name already exists is skipped.
#
#   ./scripts/seed.sh                  # dev profile (H2, no login) or ./scripts/backend-sso.sh
#   SEED_USER=bob ./scripts/seed.sh    # secured mode: register as another author
#
# In secured mode it signs in to the local Keycloak as alice (Prudence author) to get a token.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API="${PRUDENCE_URL:-http://127.0.0.1:8099}/api/registry/systems"
ISSUER="${OIDC_ISSUER:-http://localhost:8081/realms/chestertown}"
SEED_USER="${SEED_USER:-alice}"

code="$(curl -s -o /dev/null -w '%{http_code}' "$API" || true)"
case "$code" in
  200) TOKEN=""; echo "Prudence without login (dev profile)" ;;
  401)
    TOKEN="$(curl -sf "$ISSUER/protocol/openid-connect/token" -d grant_type=password -d client_id=prudence-spa \
      -d username="$SEED_USER" -d password="$SEED_USER" \
      | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')" || {
      echo "could not sign in as $SEED_USER at $ISSUER (is ./scripts/keycloak.sh running?)" >&2; exit 1; }
    echo "Prudence with login — registering as $SEED_USER" ;;
  000) echo "Prudence is not reachable at $API" >&2; exit 1 ;;
  *) echo "unexpected HTTP $code from $API" >&2; exit 1 ;;
esac

API="$API" TOKEN="$TOKEN" python3 -I - "$HERE/seed-systems.json" <<'PY'
import json, os, sys, urllib.request, urllib.error

api, token = os.environ["API"], os.environ["TOKEN"]
headers = {"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})}

def call(method, body=None):
    req = urllib.request.Request(api, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(req) as r:
        return json.load(r)

existing = {s["name"] for s in call("GET")}
failed = 0
for system in json.load(open(sys.argv[1])):
    name = system["name"]
    if name in existing:
        print(f"  = {name}: already registered, skipped")
        continue
    try:
        created = call("POST", system)
        print(f"  + {name}: {created['riskTier']} · {created['approvalStatus']} · {created['id']}")
    except urllib.error.HTTPError as e:
        failed += 1
        print(f"  ! {name}: HTTP {e.code} {e.read().decode()[:200]}", file=sys.stderr)
sys.exit(1 if failed else 0)
PY
