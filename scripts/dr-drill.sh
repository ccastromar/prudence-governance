#!/usr/bin/env bash
# Disaster-recovery drill on one machine, no Docker: runs AOS, Prudence and Ernest with real
# data, backs them up the way production does (pg_dump, mongodump, the AOS journal, the evidence
# key), destroys everything, restores it and checks the suite is exactly what it was, and still
# works. Measures each restore, so the recovery time objective in the operator guide is measured,
# not guessed. Prints a PASS/FAIL table and the timings; exit 1 if any check fails.
#
#   ./scripts/dr-drill.sh
#
# Isolated like scripts/e2e-suite.sh: its own ports (Prudence :28099, OPA :28181, Ernest :23001,
# AOS :28080, mocks :29000/:29435), PostgreSQL database prudence_drill and MongoDB database
# ernest_drill (both dropped at the end); it never stops anything it did not start. Keycloak is
# the shared dev IdP on :8081 (realm chestertown; test credentials only).
#
# Needs the sibling checkouts aos-agentic-orchestration-system and ai-blockchain-provenance, and
# go, node, mvn (JDK 25+), opa, psql, pg_dump, pg_restore, mongosh, mongodump and mongorestore.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
AOS="$REPO/../aos-agentic-orchestration-system"
ERNEST="$REPO/../ai-blockchain-provenance"
ISSUER="http://localhost:8081/realms/chestertown"
TOKEN_URL="$ISSUER/protocol/openid-connect/token"
P_PORT=28099 OPA_PORT=28181 E_PORT=23001 A_PORT=28080 MOCK_PORT=29000 LLM_PORT=29435
PG_DB=prudence_drill PG_PASSWORD=prudence-drill MONGO_DB=ernest_drill
ERNEST_KEY=drill-write-key
STATE="$(mktemp -d "${TMPDIR:-/tmp}/chestertown-drill.XXXXXX")"
BACKUP="$STATE/backup"
PSQL="$(command -v psql || echo "$(pg_config --bindir 2>/dev/null)/psql")"

mkdir -p "$STATE/pids" "$BACKUP"
RESULTS=() TIMINGS=() FAILED=0
log() { printf '  %s\n' "$*"; }
die() { echo "drill: $*" >&2; exit 2; }
started() { echo "$2" >"$STATE/pids/$1"; }
stop() {
  local f="$STATE/pids/$1" p
  [ -f "$f" ] || return 0
  p="$(cat "$f")"; rm -f "$f"
  pkill -TERM -P "$p" 2>/dev/null || true
  kill -TERM "$p" 2>/dev/null || true
  for _ in $(seq 1 30); do kill -0 "$p" 2>/dev/null || break; sleep 0.5; done
  kill -KILL "$p" 2>/dev/null || true
}
drop_databases() {
  mongosh --quiet "mongodb://127.0.0.1:27017/$MONGO_DB" --eval 'db.dropDatabase()' >/dev/null 2>&1 || true
  "$PSQL" -h 127.0.0.1 -d postgres -qc "drop database if exists $PG_DB" >/dev/null 2>&1 || true
}
cleanup() {
  set +e
  for f in "$STATE"/pids/*; do [ -f "$f" ] && stop "$(basename "$f")"; done
  drop_databases
  "$PSQL" -h 127.0.0.1 -d postgres -qc "drop role if exists $PG_DB" >/dev/null 2>&1
  if [ "${KEEP_LOGS:-}" = 1 ]; then echo "logs kept in $STATE"; else rm -rf "$STATE"; fi
}
trap cleanup EXIT INT TERM
wait_http() {  # wait_http URL SECONDS NAME
  for _ in $(seq 1 "$2"); do curl -sf -o /dev/null "$1" && return 0; sleep 1; done
  die "$3 did not come up ($1); run with KEEP_LOGS=1 to keep the logs in $STATE"
}
record() {  # record GROUP CASE OK DETAIL
  local status=PASS; [ "$3" = true ] || { status=FAIL; FAILED=$((FAILED + 1)); }
  RESULTS+=("$(printf '%-10s %-54s %-5s %s' "$1" "$2" "$status" "$4")")
}
now() { python3 -I -c 'import time; print(time.monotonic())'; }
timed() {  # timed LABEL SINCE: record seconds since SINCE
  TIMINGS+=("$(python3 -I -c 'import sys,time; print(f"{sys.argv[1]:<44} {time.monotonic()-float(sys.argv[2]):6.1f} s")' "$1" "$2")")
}
json() { python3 -I -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
token() {  # token CLIENT USER
  curl -sf "$TOKEN_URL" -d grant_type=password -d client_id="$1" -d username="$2" -d password="$2" \
    -d scope=openid | json 'd["access_token"]'
}
ernest() { curl -sf -H "X-Ernest-Api-Key: $ERNEST_KEY" "http://127.0.0.1:$E_PORT$1"; }
prudence() { curl -sf -H "Authorization: Bearer $(token prudence-spa alice)" "http://127.0.0.1:$P_PORT$1"; }

# ── preflight ──────────────────────────────────────────────────────────────────────────────────
for cmd in go node mvn opa mongosh mongodump mongorestore curl python3; do
  command -v "$cmd" >/dev/null || die "$cmd not found"
done
# pg_dump and pg_restore of the server's major version: a newer client writes settings an older
# server refuses (pg_dump 18 emits transaction_timeout, unknown to PostgreSQL 16).
PG_MAJOR="$("$PSQL" -h 127.0.0.1 -d postgres -Atc 'show server_version_num' | cut -c1-2)"
PG_BIN=""
for dir in "$(dirname "$(command -v pg_dump 2>/dev/null || echo /nonexistent/x)")" \
           "$(pg_config --bindir 2>/dev/null || true)" \
           "$(brew --prefix 2>/dev/null || echo /opt/homebrew)/opt/postgresql@$PG_MAJOR/bin" \
           "/usr/lib/postgresql/$PG_MAJOR/bin"; do
  if [ -x "$dir/pg_dump" ] && [ -x "$dir/pg_restore" ] \
     && "$dir/pg_dump" --version | grep -qE " $PG_MAJOR(\.|$)"; then PG_BIN="$dir"; break; fi
done
[ -n "$PG_BIN" ] || die "no pg_dump/pg_restore $PG_MAJOR found: install the client of the server's major version"
[ -d "$AOS/cmd/aos" ] || die "AOS checkout not found at $AOS"
[ -d "$ERNEST/backend" ] || die "Ernest checkout not found at $ERNEST"
for port in $P_PORT $OPA_PORT $E_PORT $A_PORT $MOCK_PORT $LLM_PORT; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then die "port $port is in use"; fi
done
"$PSQL" -h 127.0.0.1 -d postgres -Atc 'select 1' >/dev/null 2>&1 || die "PostgreSQL not reachable on 127.0.0.1:5432"
mongosh --quiet mongodb://127.0.0.1:27017 --eval 'db.runCommand({ping:1}).ok' >/dev/null 2>&1 \
  || die "MongoDB not reachable on 127.0.0.1:27017"
if [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25+ 2>/dev/null || echo "${JAVA_HOME:-}")"; export JAVA_HOME
fi
echo "Chestertown disaster-recovery drill (state in $STATE; PostgreSQL $PG_MAJOR tools in $PG_BIN)"

if ! curl -sf -o /dev/null "$ISSUER/.well-known/openid-configuration"; then
  log "starting Keycloak (scripts/keycloak.sh)"
  "$HERE/keycloak.sh" >"$STATE/keycloak.log" 2>&1 &
  started keycloak $!
  wait_http "$ISSUER/.well-known/openid-configuration" 180 Keycloak
fi

# ── the suite ──────────────────────────────────────────────────────────────────────────────────
opa run --server --addr "127.0.0.1:$OPA_PORT" "$REPO/policy/opa" >"$STATE/opa.log" 2>&1 &
started opa $!
wait_http "http://127.0.0.1:$OPA_PORT/health" 30 OPA

# The pseudonym key is the one secret the drill backs up and restores like production does.
PSEUDONYM_KEY_FILE="$STATE/prudence-pseudonym.key"
python3 -I -c 'import secrets; print(secrets.token_hex(32))' >"$PSEUDONYM_KEY_FILE"

start_ernest() {
  (cd "$ERNEST/backend" && ERNEST_API_KEY=$ERNEST_KEY ERNEST_READ_API_KEY= \
    ANCHOR_PROVIDER= INFURA_URL= PRIVATE_KEY= CONTRACT_ADDRESS= WEBHOOK_URL= MLFLOW_TRACKING_URI= \
    CORS_ORIGIN='*' MONGODB_URI="mongodb://127.0.0.1:27017/$MONGO_DB" PORT=$E_PORT \
    exec node dist/main.js) >>"$STATE/ernest.log" 2>&1 &
  started ernest $!
  wait_http "http://127.0.0.1:$E_PORT/health" 90 Ernest
}
if [ ! -f "$ERNEST/backend/dist/main.js" ]; then
  log "building Ernest backend"
  (cd "$ERNEST" && npx -y pnpm@11.7.0 --filter ernest-backend run build >"$STATE/ernest-build.log" 2>&1)
fi
drop_databases
log "Ernest :$E_PORT (database $MONGO_DB)"
start_ernest

"$PSQL" -h 127.0.0.1 -d postgres -qc "drop role if exists $PG_DB" \
  -c "create role $PG_DB login password '$PG_PASSWORD'" -c "create database $PG_DB owner $PG_DB" >/dev/null
jar="$(ls "$REPO"/target/prudence-*.jar 2>/dev/null | grep -v plain | sort -V | tail -1 || true)"
if [ -z "$jar" ]; then
  log "building Prudence"
  (cd "$REPO" && mvn -q -B -ntp -DskipTests package >"$STATE/prudence-build.log" 2>&1)
  jar="$(ls "$REPO"/target/prudence-*.jar | grep -v plain | sort -V | tail -1)"
fi
start_prudence() {
  SPRING_PROFILES_ACTIVE="" SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="$ISSUER" \
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=prudence-api \
  PRUDENCE_EVIDENCE_PSEUDONYM_KEY_FILE="$PSEUDONYM_KEY_FILE" \
  PRUDENCE_IDENTITY_ROLE_MAPPING=prudence-reader=reader,prudence-author=author,prudence-system-approver=system-approver,prudence-policy-reviewer=policy-reviewer,prudence-admin=admin \
  PRUDENCE_DB_URL="jdbc:postgresql://127.0.0.1:5432/$PG_DB" PRUDENCE_DB_USER=$PG_DB PRUDENCE_DB_PASSWORD=$PG_PASSWORD \
  PRUDENCE_ERNEST_BASE_URL="http://127.0.0.1:$E_PORT" PRUDENCE_ERNEST_API_KEY=$ERNEST_KEY \
  SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT=1025 MANAGEMENT_HEALTH_MAIL_ENABLED=false \
  PRUDENCE_ALERTS_GOVERNANCE_TO=governance@example.com PRUDENCE_ALERTS_OPERATIONS_TO=ops@example.com \
  PRUDENCE_CORS_ORIGINS=http://localhost:5200 PRUDENCE_CONSOLE_URL=http://localhost:5200 \
    java -jar "$jar" --server.port=$P_PORT --prudence.opa.base-url="http://127.0.0.1:$OPA_PORT" \
    --logging.level.root=WARN >>"$STATE/prudence.log" 2>&1 &
  started prudence $!
  wait_http "http://127.0.0.1:$P_PORT/actuator/health" 180 Prudence
}
log "Prudence :$P_PORT (database $PG_DB)"
start_prudence
log "seeding the public MVP systems"
PRUDENCE_URL="http://127.0.0.1:$P_PORT" SEED_AUTHOR_TOKEN="$(token prudence-spa alice)" \
  SEED_REVIEWER_ONE_TOKEN="$(token prudence-spa bob)" SEED_REVIEWER_TWO_TOKEN="$(token prudence-spa carol)" \
  "$HERE/seed-mvp.sh" >"$STATE/seed.log" 2>&1
PRUDENCE_URL="http://127.0.0.1:$P_PORT" "$HERE/aos-demo-env.sh" >"$STATE/aos.env" 2>"$STATE/aos-env.log"
set -a; . "$STATE/aos.env"; set +a

(cd "$AOS" && go build -o "$STATE/aos" ./cmd/aos && go build -o "$STATE/aos-mocks" ./cmd/mocks \
  && go build -o "$STATE/aos-mock-llm" ./cmd/mock-ollama)
(cd "$AOS" && AOS_MOCK_PORT=$MOCK_PORT exec "$STATE/aos-mocks") >"$STATE/mocks.log" 2>&1 &
started mocks $!
(cd "$AOS" && MOCK_OLLAMA_PORT=$LLM_PORT exec "$STATE/aos-mock-llm") >"$STATE/llm.log" 2>&1 &
started llm $!
JOURNAL="$STATE/aos-audit.jsonl"
log "AOS :$A_PORT (journal $JOURNAL)"
(cd "$AOS" && AOS_WORKSPACE_ROOT="$AOS" APP_ENV=dev AOS_STATE_STORE=memory ALLOW_LOCAL_TOOLS=false \
  AOS_TOOL_INTERNAL_HOSTS=127.0.0.1 AOS_IAM_URL= QDRANT_URL= REDIS_ADDR= REDIS_PASSWORD= \
  AOS_MOCK_BASE_URL="http://127.0.0.1:$MOCK_PORT" AOS_CALLBACK_BASE_URL="http://127.0.0.1:$A_PORT" \
  AUDIT_LOG_PATH="$JOURNAL" ERNEST_INGESTOR_URL= AOS_EVIDENCE_KAFKA_BROKERS= \
  LLM_ENGINE=ollama OLLAMA_BASE_URL="http://127.0.0.1:$LLM_PORT" LLM_MODEL=aos-demo:deterministic \
  OLLAMA_INTENT_MODEL=aos-demo:deterministic \
  AOS_JWT_ISSUER="$ISSUER" AOS_JWK_URL="$ISSUER/protocol/openid-connect/certs" \
  AOS_ROLE_MAP=aos-admin=admin,aos-operator=operator,aos-auditor=guest \
  PRUDENCE_PDP_URL="http://127.0.0.1:$P_PORT" PRUDENCE_PDP_TOKEN_URL="$TOKEN_URL" \
  PRUDENCE_PDP_CLIENT_ID=pdp-client PRUDENCE_PDP_CLIENT_SECRET=pdp-dev-secret PRUDENCE_PDP_SCOPE=pdp.decide \
  exec "$STATE/aos" -port $A_PORT) >"$STATE/aos.log" 2>&1 &
started aos $!
wait_http "http://127.0.0.1:$A_PORT/health/ready" 60 AOS

log "running governed tasks"
body='{"lang":"en","message":"Run the governed customer-support lookup demo"}'
for _ in 1 2; do
  curl -s -o /dev/null -X POST "http://127.0.0.1:$A_PORT/ask" -H "Authorization: Bearer $(token aos-spa alice)" \
    -H 'Content-Type: application/json' -d "$body"
done
# Wait until the journal has the runs and Prudence's outbox has delivered to Ernest.
for _ in $(seq 1 60); do
  [ -s "$JOURNAL" ] && [ "$(ernest /api/verify | json 'd.get("chainLength",0)')" -gt 1 ] \
    && [ "$(prudence /api/evidence/dead-letters | json 'len(d) if isinstance(d,list) else len(d.get("items",[]))')" = 0 ] && break
  sleep 1
done
sleep 5  # let the last decisions and reviews leave the outbox

# ── what the suite looks like before the disaster ──────────────────────────────────────────────
systems() { prudence /api/registry/systems | json 'sorted((s["id"],s["approvalStatus"],s.get("riskTier")) for s in d)'; }
posture() { prudence /api/posture | json '[d["overall"]]+sorted((s["id"],s["status"]) for s in d["systems"])'; }
chain() { local n; n="$(ernest /api/verify | json 'd["chainLength"]')"; echo "$n $(ernest "/api/blocks/$((n - 1))" | json 'd["hash"]')"; }
journal_head() { "$STATE/aos" -verify-audit "$JOURNAL" 2>/dev/null | tail -1; }  # {"valid":…,"records":…,"head_hash":…}
reviewers() {  # every reviewer Prudence recorded, from its own records
  for id in $(prudence /api/registry/systems | json '" ".join(s["id"] for s in d)'); do
    prudence "/api/registry/systems/$id/reviews" | json '"\n".join(r["reviewer"] for r in d if r.get("reviewer"))'
  done | sort -u
}
BEFORE_SYSTEMS="$(systems)"; BEFORE_POSTURE="$(posture)"; BEFORE_CHAIN="$(chain)"; BEFORE_JOURNAL="$(journal_head)"
BEFORE_REVIEWERS="$(reviewers)"
log "before: $(echo "$BEFORE_SYSTEMS" | python3 -I -c 'import ast,sys; print(len(ast.literal_eval(sys.stdin.read())))') systems, chain ${BEFORE_CHAIN%% *} blocks, journal: $BEFORE_JOURNAL"

# ── backup, as production does ─────────────────────────────────────────────────────────────────
t="$(now)"
stop aos  # a consistent journal copy, like scripts/backup-mvp.sh does for AOS's state
PGPASSWORD=$PG_PASSWORD "$PG_BIN/pg_dump" -h 127.0.0.1 -U $PG_DB -Fc -f "$BACKUP/prudence.dump" $PG_DB
mongodump --quiet --uri="mongodb://127.0.0.1:27017/$MONGO_DB" --archive="$BACKUP/ernest.archive.gz" --gzip
cp "$JOURNAL" "$BACKUP/aos-audit.jsonl"
cp "$PSEUDONYM_KEY_FILE" "$BACKUP/prudence-pseudonym.key"
timed "backup (PostgreSQL, MongoDB, journal, key)" "$t"

# ── disaster: everything is gone ───────────────────────────────────────────────────────────────
log "destroying Prudence, Ernest and AOS data"
stop prudence; stop ernest
drop_databases
rm -f "$JOURNAL" "$PSEUDONYM_KEY_FILE"

# ── restore ────────────────────────────────────────────────────────────────────────────────────
T0="$(now)"
cp "$BACKUP/prudence-pseudonym.key" "$PSEUDONYM_KEY_FILE"
t="$(now)"
mongorestore --quiet --uri="mongodb://127.0.0.1:27017" --archive="$BACKUP/ernest.archive.gz" --gzip --drop
timed "restore MongoDB (Ernest)" "$t"
t="$(now)"
"$PSQL" -h 127.0.0.1 -d postgres -qc "create database $PG_DB owner $PG_DB" >/dev/null
PGPASSWORD=$PG_PASSWORD "$PG_BIN/pg_restore" -h 127.0.0.1 -U $PG_DB -d $PG_DB --no-owner "$BACKUP/prudence.dump"
timed "restore PostgreSQL (Prudence)" "$t"
cp "$BACKUP/aos-audit.jsonl" "$JOURNAL"
t="$(now)"; start_ernest; timed "Ernest up after restore" "$t"
t="$(now)"; start_prudence; timed "Prudence up after restore" "$t"
timed "whole restore, data to both apps answering" "$T0"

# ── the suite is what it was ───────────────────────────────────────────────────────────────────
AFTER_SYSTEMS="$(systems)"; AFTER_POSTURE="$(posture)"; AFTER_CHAIN="$(chain)"; AFTER_JOURNAL="$(journal_head)"
record prudence "registry: same systems and approval states" "$([ "$AFTER_SYSTEMS" = "$BEFORE_SYSTEMS" ] && echo true || echo false)" ""
record prudence "posture: same overall and per-system status" "$([ "$AFTER_POSTURE" = "$BEFORE_POSTURE" ] && echo true || echo false)" "$(echo "$AFTER_POSTURE" | cut -c1-40)"
record ernest "chain: same length and head hash" "$([ "$AFTER_CHAIN" = "$BEFORE_CHAIN" ] && echo true || echo false)" "${AFTER_CHAIN%% *} blocks"
valid="$(ernest /api/verify | json 'd["isValid"]')"
record ernest "chain verifies after restore" "$([ "$valid" = True ] && echo true || echo false)" "isValid=$valid"
record aos "journal: same records and head, chain verifies" "$([ "$AFTER_JOURNAL" = "$BEFORE_JOURNAL" ] && [[ "$AFTER_JOURNAL" == *'"valid":true'* ]] && echo true || echo false)" "$AFTER_JOURNAL"

# The restored key still links Prudence's reviewers to their pseudonyms in Ernest; another key
# does not: why the key is in the backup.
provenance="$(ernest /api/provenances/prudence-reviews)"
pseudonym() { python3 -I -c 'import hashlib,hmac,sys; print(hmac.new(open(sys.argv[1]).read().strip().encode(), sys.argv[2].encode(), hashlib.sha256).hexdigest())' "$1" "$2"; }
linked=0 total=0 wrong=0
python3 -I -c 'import secrets; print(secrets.token_hex(32))' >"$STATE/other.key"
for r in $BEFORE_REVIEWERS; do
  total=$((total + 1))
  echo "$provenance" | grep -q "$(pseudonym "$PSEUDONYM_KEY_FILE" "$r")" && linked=$((linked + 1))
  echo "$provenance" | grep -q "$(pseudonym "$STATE/other.key" "$r")" && wrong=$((wrong + 1))
done
record keys "restored key links every reviewer to Ernest" "$([ "$total" -gt 0 ] && [ "$linked" = "$total" ] && echo true || echo false)" "$linked/$total reviewers"
record keys "another key links none" "$([ "$wrong" = 0 ] && echo true || echo false)" "$wrong/$total"

# Still works: new evidence is appended to the restored chain, and it still verifies.
model="drill-after-restore"
ernest /api/models >/dev/null
curl -sf -o /dev/null -X POST "http://127.0.0.1:$E_PORT/api/models" -H "X-Ernest-Api-Key: $ERNEST_KEY" \
  -H 'Content-Type: application/json' \
  -d "{\"modelId\":\"$model\",\"modelName\":\"Drill\",\"version\":\"1\",\"mlflow\":{\"modelHash\":\"$(printf a%.0s {1..64})\",\"gitCommit\":\"0000000\"}}" || true
grown="$(ernest /api/verify | json 'd["chainLength"]')"; valid="$(ernest /api/verify | json 'd["isValid"]')"
record ernest "new evidence appends to the restored chain" "$([ "$grown" -gt "${AFTER_CHAIN%% *}" ] && [ "$valid" = True ] && echo true || echo false)" "$grown blocks, isValid=$valid"

echo
printf '%-10s %-54s %-5s %s\n' GROUP CASE RESULT DETAIL
printf '%s\n' "${RESULTS[@]}"
echo
echo "Timings"
printf '  %s\n' "${TIMINGS[@]}"
[ "$FAILED" = 0 ] || { echo "$FAILED check(s) failed"; exit 1; }
