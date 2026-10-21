#!/usr/bin/env bash
# The isolated stack the conformance run talks to: OPA on :18181 with this folder's grants and
# Prudence (dev profile, its own throw-away H2) on :18099. It does not touch your dev Prudence
# (:8099), its data or policy/opa/data.json. Ctrl+C stops both.
#
#   integrations/conformance/stack.sh        # then, in another terminal: run.py
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="$(mktemp -d -t prudence-conformance)"

if [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25+ 2>/dev/null || echo "${JAVA_HOME:-}")"; export JAVA_HOME
fi
command -v opa >/dev/null || { echo "opa not found (brew install opa)" >&2; exit 1; }
for port in 18181 18099; do
  if lsof -nP -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1; then echo "port $port is in use" >&2; exit 1; fi
done

jar="$(ls "$REPO"/target/prudence-*.jar 2>/dev/null | grep -v plain | head -1 || true)"
if [ -z "$jar" ] || [ -n "${REBUILD:-}" ]; then
  echo "building Prudence…"; (cd "$REPO" && mvn -q -DskipTests package)
  jar="$(ls "$REPO"/target/prudence-*.jar | grep -v plain | head -1)"
fi

mkdir -p "$WORK/policy"
cp "$REPO/policy/opa/prudence.rego" "$WORK/policy/"
cp "$HERE/opa-data.json" "$WORK/policy/data.json"
trap 'kill 0; rm -rf "$WORK"' EXIT
opa run --server --addr 127.0.0.1:18181 "$WORK/policy" >"$WORK/opa.log" 2>&1 &
echo "OPA on :18181, Prudence on :18099 (work dir $WORK)"
cd "$WORK"  # the dev H2 file lands here
SPRING_PROFILES_ACTIVE=dev java -jar "$jar" --server.port=18099 \
  --prudence.opa.base-url=http://127.0.0.1:18181 --prudence.ernest.base-url=http://127.0.0.1:9 \
  --logging.level.root=WARN
