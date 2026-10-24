#!/usr/bin/env bash
# The isolated stack for the conformance run WITH login (run-sso.py): Prudence in its secured
# configuration (JWT from the local Keycloak, PostgreSQL, the production start checks) on
# :18099; OPA on :18181 serving .sso/policy, which starts with NO grant for the runtimes
# (run-sso.py adds them with scripts/provision-runtime.py, as an adopter would); and Ernest on
# :13001 with its own MongoDB database, so the whole suite is in the loop. ERNEST=off skips
# Ernest (needs MongoDB and the sibling ai-blockchain-provenance checkout, built).
#
#   scripts/keycloak.sh                       # first, in another terminal (realm chestertown)
#   integrations/conformance/stack-sso.sh     # then run-sso.py in a third
#
# Leaves your own Prudence, its databases and policy/opa/data.json alone. Needs PostgreSQL on
# 127.0.0.1:5432 (brew services start postgresql@16); creates role/database
# prudence_conformance on first run. Test credentials only.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
STATE="$HERE/.sso"
ISSUER="${OIDC_ISSUER:-http://localhost:8081/realms/chestertown}"
DB=prudence_conformance; DB_PASSWORD=prudence-conformance
PSQL="$(command -v psql || echo "$(pg_config --bindir 2>/dev/null)/psql")"

if [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25+ 2>/dev/null || echo "${JAVA_HOME:-}")"; export JAVA_HOME
fi
command -v opa >/dev/null || { echo "opa not found (brew install opa)" >&2; exit 1; }
curl -sf -o /dev/null "$ISSUER/.well-known/openid-configuration" || {
  echo "Keycloak issuer not reachable: $ISSUER (start scripts/keycloak.sh)" >&2; exit 1; }
"$PSQL" -h 127.0.0.1 -d postgres -Atc 'select 1' >/dev/null 2>&1 || {
  echo "PostgreSQL not reachable on 127.0.0.1:5432" >&2; exit 1; }
ERNEST_HOME="${ERNEST_HOME:-$REPO/../ai-blockchain-provenance}"
WITH_ERNEST=true; [ "${ERNEST:-on}" = off ] && WITH_ERNEST=false
if $WITH_ERNEST; then
  [ -f "$ERNEST_HOME/backend/dist/main.js" ] || { echo "Ernest not built at $ERNEST_HOME (or ERNEST=off)" >&2; exit 1; }
  mongosh --quiet mongodb://127.0.0.1:27017/test --eval 'db.runCommand({ping:1}).ok' >/dev/null 2>&1 || {
    echo "MongoDB not reachable on 127.0.0.1:27017 (or ERNEST=off)" >&2; exit 1; }
fi
for port in 18181 18099 13001; do
  if lsof -nP -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1; then echo "port $port is in use" >&2; exit 1; fi
done

# A fresh database each run, so every run starts from the same state.
if [ "$("$PSQL" -h 127.0.0.1 -d postgres -Atc "select 1 from pg_roles where rolname='$DB'")" != 1 ]; then
  "$PSQL" -h 127.0.0.1 -d postgres -qc "create role $DB login password '$DB_PASSWORD'"
fi
"$PSQL" -h 127.0.0.1 -d postgres -qc "drop database if exists $DB" -qc "create database $DB owner $DB"

jar="$(ls "$REPO"/target/prudence-*.jar 2>/dev/null | grep -v plain | head -1 || true)"
if [ -z "$jar" ] || [ -n "${REBUILD:-}" ]; then
  echo "building Prudence…"; (cd "$REPO" && mvn -q -DskipTests package)
  jar="$(ls "$REPO"/target/prudence-*.jar | grep -v plain | head -1)"
fi

# OPA: the real policy, and data with no grant for the runtime under test.
rm -rf "$STATE"; mkdir -p "$STATE/policy"
cp "$REPO/policy/opa/prudence.rego" "$STATE/policy/"
echo '{"prudence": {"roles_of": {}, "role_permissions": {}}}' > "$STATE/policy/data.json"
trap 'kill 0' EXIT
if $WITH_ERNEST; then
  # A fresh chain each run; open access, no anchoring or webhooks (as scripts/ernest.sh).
  mongosh --quiet mongodb://127.0.0.1:27017/ernest_conformance --eval 'db.dropDatabase()' >/dev/null
  (cd "$ERNEST_HOME/backend" && EVIDENCE_BATCHING=on ERNEST_API_KEY= ERNEST_READ_API_KEY= ANCHOR_PROVIDER= \
    INFURA_URL= PRIVATE_KEY= CONTRACT_ADDRESS= WEBHOOK_URL= MLFLOW_TRACKING_URI= CORS_ORIGIN='*' \
    MONGODB_URI=mongodb://127.0.0.1:27017/ernest_conformance PORT=13001 \
    exec node dist/main.js >"$STATE/ernest.log" 2>&1) &
  ERNEST_URL=http://127.0.0.1:13001
else
  ERNEST_URL=http://127.0.0.1:9
fi
echo "$ERNEST_URL" > "$STATE/ernest-url"
opa run --server --watch --addr 127.0.0.1:18181 "$STATE/policy" >"$STATE/opa.log" 2>&1 &

export SPRING_PROFILES_ACTIVE=""
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="$ISSUER"
# The audience the production start check requires; provision-runtime.py adds it to each runtime.
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES="${PRUDENCE_OIDC_AUDIENCE:-prudence-api}"
# The realm's suite-wide role names (prudence-author, …) mapped to Prudence's own.
export PRUDENCE_IDENTITY_ROLE_MAPPING="${PRUDENCE_IDENTITY_ROLE_MAPPING:-prudence-reader=reader,prudence-author=author,prudence-system-approver=system-approver,prudence-policy-reviewer=policy-reviewer,prudence-admin=admin}"
export PRUDENCE_DB_URL="jdbc:postgresql://127.0.0.1:5432/$DB" PRUDENCE_DB_USER=$DB PRUDENCE_DB_PASSWORD=$DB_PASSWORD
# Ernest runs with open access; Prudence's start check only needs a non-blank key.
export PRUDENCE_ERNEST_BASE_URL="$ERNEST_URL" PRUDENCE_ERNEST_API_KEY=conformance
export SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT=1025 MANAGEMENT_HEALTH_MAIL_ENABLED=false
export PRUDENCE_ALERTS_GOVERNANCE_TO=governance@example.com PRUDENCE_ALERTS_OPERATIONS_TO=ops@example.com
export PRUDENCE_CORS_ORIGINS=http://localhost:5200 PRUDENCE_CONSOLE_URL=http://localhost:5200
echo "OPA :18181 (data in $STATE/policy), Prudence secured :18099, Ernest $ERNEST_URL, issuer $ISSUER"
# A short grace, so a missing execution evidence shows up during the run, not 15 minutes later.
java -jar "$jar" --server.port=18099 --prudence.opa.base-url=http://127.0.0.1:18181 \
  --prudence.drift.source-grace=PT20S --logging.level.root=WARN
