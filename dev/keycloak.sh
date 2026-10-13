#!/usr/bin/env bash
# Local Keycloak (dev IdP) for the Chestertown trio on http://localhost:8081, importing the
# test realm `chestertown` (users alice / bob / auditor — see dev/README.md). No Docker.
#
#   ./dev/keycloak.sh                       # finds ../keycloak-* next to this repo
#   KEYCLOAK_HOME=/path/to/keycloak ./dev/keycloak.sh
#
# Download once: https://github.com/keycloak/keycloak/releases (any 26.x .tar.gz), unpack it
# next to this repo. Admin console: http://localhost:8081 (admin/admin, dev only).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
PORT="${KEYCLOAK_PORT:-8081}"

if [ -z "${KEYCLOAK_HOME:-}" ]; then
  KEYCLOAK_HOME="$(ls -d "$REPO"/../keycloak-*/ 2>/dev/null | sort -V | tail -1)"
fi
KEYCLOAK_HOME="${KEYCLOAK_HOME%/}"
[ -x "$KEYCLOAK_HOME/bin/kc.sh" ] || {
  echo "Keycloak not found — unpack a 26.x release next to this repo or set KEYCLOAK_HOME" >&2; exit 1; }
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "port $PORT is already in use:" >&2; lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2; exit 1
fi

# The realm is imported only if it does not exist yet; edits to the JSON need a realm delete
# (or a wipe of $KEYCLOAK_HOME/data) to be picked up.
mkdir -p "$KEYCLOAK_HOME/data/import"
cp "$HERE/chestertown-realm.json" "$KEYCLOAK_HOME/data/import/"

echo "Keycloak $(cat "$KEYCLOAK_HOME/version.txt" 2>/dev/null) on http://localhost:$PORT"
echo "issuer: http://localhost:$PORT/realms/chestertown"
cd "$KEYCLOAK_HOME"
KC_BOOTSTRAP_ADMIN_USERNAME="${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}" \
KC_BOOTSTRAP_ADMIN_PASSWORD="${KC_BOOTSTRAP_ADMIN_PASSWORD:-admin}" \
  exec bin/kc.sh start-dev --http-port "$PORT" --import-realm
