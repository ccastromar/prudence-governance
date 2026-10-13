#!/usr/bin/env bash
# Local OPA sidecar for Prudence's PDP (dev). Serves policy/opa/ on 127.0.0.1:8181, where
# application.yml's prudence.opa.base-url points, and reloads on policy edits (--watch).
#
#   ./dev/opa.sh              # or OPA_PORT=18181 ./dev/opa.sh
#
# Requires the opa binary (brew install opa). Not for production: there the policy is a
# signed bundle served to the sidecar (see policy/opa/README.md).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POLICY="$HERE/../policy/opa"
PORT="${OPA_PORT:-8181}"

command -v opa >/dev/null || { echo "opa not found — install it with: brew install opa" >&2; exit 1; }
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "port $PORT is already in use:" >&2; lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2; exit 1
fi

opa test "$POLICY" >/dev/null || { echo "policy tests failed — run: opa test policy/opa -v" >&2; exit 1; }

echo "OPA on http://127.0.0.1:$PORT  (policy: policy/opa, decision: /v1/data/prudence/allow)"
[ "$PORT" = 8181 ] || echo "start Prudence with --prudence.opa.base-url=http://127.0.0.1:$PORT"
exec opa run --server --watch --addr "127.0.0.1:$PORT" "$POLICY"
