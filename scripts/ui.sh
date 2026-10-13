#!/usr/bin/env bash
# Prudence console WITHOUT login on http://localhost:5200 (pair with ./scripts/backend.sh).
# For login, use ./scripts/ui-sso.sh instead.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE/../ui"
[ -d node_modules ] || npm install
unset VITE_OIDC_ISSUER
exec npm run dev
