#!/usr/bin/env bash
# Prudence UI with SSO (Authorization Code + PKCE, client prudence-spa) on http://localhost:5200.
# Pair it with ./scripts/keycloak.sh and ./scripts/backend-sso.sh. Users: alice/alice, bob/bob,
# auditor/auditor (see scripts/README.md).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE/../ui"
[ -d node_modules ] || npm install
export VITE_OIDC_ISSUER="${VITE_OIDC_ISSUER:-http://localhost:8081/realms/chestertown}"
export VITE_OIDC_CLIENT_ID="${VITE_OIDC_CLIENT_ID:-prudence-spa}"
exec npm run dev -- --host "${VITE_HOST:-127.0.0.1}"
