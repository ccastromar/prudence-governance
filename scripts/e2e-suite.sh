#!/usr/bin/env bash
# End-to-end run of the whole Chestertown suite on one machine, no Docker: AOS asks Prudence,
# runs or refuses, ships its execution evidence over Kafka to Ernest, and Prudence sees it.
# Then it breaks one piece at a time (Kafka, Ernest, Prudence) and checks the suite recovers or
# fails closed. Prints a PASS/FAIL table; exit 1 if any case fails.
#
#   ./scripts/keycloak.sh            # optional: started (and stopped) by this script if not running
#   ./scripts/e2e-suite.sh
#
# Isolated from your own local setup: its own ports (Prudence :18099, OPA :18181, Ernest :13001,
# AOS :18080, Kafka :19092), a throw-away Kafka in a temp dir, PostgreSQL database prudence_e2e
# and MongoDB database ernest_e2e (both dropped at the end). It refuses to start if one of its
# ports is busy and never stops anything it did not start. Keycloak is the shared dev IdP on
# :8081 with the realm chestertown (scripts/chestertown-realm.json); test credentials only.
#
# Needs the sibling checkouts aos-agentic-orchestration-system and ai-blockchain-provenance, and
# go, node, mvn (JDK 25+), opa, mongosh, psql and Kafka (brew install kafka) on the PATH.
# Not covered: the consoles (browser sign-in/sign-out), Docker images, a real LLM.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
AOS="$REPO/../aos-agentic-orchestration-system"
ERNEST="$REPO/../ai-blockchain-provenance"
ISSUER="http://localhost:8081/realms/chestertown"
TOKEN_URL="$ISSUER/protocol/openid-connect/token"
P_PORT=18099 OPA_PORT=18181 E_PORT=13001 A_PORT=18080 K_PORT=19092 KC_PORT=19093
MOCK_PORT=19000 LLM_PORT=19435 METRICS_PORT=19102
PG_DB=prudence_e2e PG_PASSWORD=prudence-e2e MONGO_DB=ernest_e2e
ERNEST_KEY=e2e-write-key
STATE="$(mktemp -d "${TMPDIR:-/tmp}/chestertown-e2e.XXXXXX")"
PSQL="$(command -v psql || echo "$(pg_config --bindir 2>/dev/null)/psql")"
KAFKA_BIN="$(brew --prefix kafka 2>/dev/null)/bin"

# ── housekeeping ───────────────────────────────────────────────────────────────────────────────
mkdir -p "$STATE/pids"
RESULTS=() FAILED=0

log() { printf '  %s\n' "$*"; }
die() { echo "e2e: $*" >&2; exit 2; }

started() { echo "$2" >"$STATE/pids/$1"; }  # started NAME PID: this script owns the process

stop() {  # stop one component this script started
  local f="$STATE/pids/$1" p
  [ -f "$f" ] || return 0
  p="$(cat "$f")"; rm -f "$f"
  pkill -TERM -P "$p" 2>/dev/null || true
  kill -TERM "$p" 2>/dev/null || true
  for _ in $(seq 1 30); do kill -0 "$p" 2>/dev/null || break; sleep 0.5; done
  kill -KILL "$p" 2>/dev/null || true
}

cleanup() {
  set +e
  for f in "$STATE"/pids/*; do [ -f "$f" ] && stop "$(basename "$f")"; done
  mongosh --quiet "mongodb://127.0.0.1:27017/$MONGO_DB" --eval 'db.dropDatabase()' >/dev/null 2>&1
  "$PSQL" -h 127.0.0.1 -d postgres -qc "drop database if exists $PG_DB" -c "drop role if exists $PG_DB" >/dev/null 2>&1
  if [ "${KEEP_LOGS:-}" = 1 ]; then echo "logs kept in $STATE"; else rm -rf "$STATE"; fi
}
trap cleanup EXIT INT TERM

wait_http() {  # wait_http URL SECONDS NAME
  for _ in $(seq 1 "$2"); do curl -sf -o /dev/null "$1" && return 0; sleep 1; done
  die "$3 did not come up ($1); logs in $STATE (run with KEEP_LOGS=1 to keep them)"
}

record() {  # record GROUP CASE OK DETAIL
  local status=PASS; [ "$3" = true ] || { status=FAIL; FAILED=$((FAILED + 1)); }
  RESULTS+=("$(printf '%-12s %-52s %-5s %s' "$1" "$2" "$status" "$4")")
}

json() { python3 -I -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

token() {  # token CLIENT USER
  curl -sf "$TOKEN_URL" -d grant_type=password -d client_id="$1" -d username="$2" -d password="$2" \
    -d scope=openid | json 'd["access_token"]'
}

# ── preflight ──────────────────────────────────────────────────────────────────────────────────
for cmd in go node mvn opa mongosh curl python3; do command -v "$cmd" >/dev/null || die "$cmd not found"; done
[ -x "$KAFKA_BIN/kafka-server-start" ] || die "Kafka not found (brew install kafka)"
[ -d "$AOS/cmd/aos" ] || die "AOS checkout not found at $AOS"
[ -d "$ERNEST/backend" ] || die "Ernest checkout not found at $ERNEST"
for port in $P_PORT $OPA_PORT $E_PORT $A_PORT $K_PORT $KC_PORT $MOCK_PORT $LLM_PORT $METRICS_PORT; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    die "port $port is in use; this run needs it free (it never stops what it did not start)"
  fi
done
"$PSQL" -h 127.0.0.1 -d postgres -Atc 'select 1' >/dev/null 2>&1 || die "PostgreSQL not reachable on 127.0.0.1:5432"
mongosh --quiet mongodb://127.0.0.1:27017 --eval 'db.runCommand({ping:1}).ok' >/dev/null 2>&1 \
  || die "MongoDB not reachable on 127.0.0.1:27017"
if [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25+ 2>/dev/null || echo "${JAVA_HOME:-}")"; export JAVA_HOME
fi

echo "Chestertown e2e (state in $STATE)"

# ── Keycloak ───────────────────────────────────────────────────────────────────────────────────
if ! curl -sf -o /dev/null "$ISSUER/.well-known/openid-configuration"; then
  log "starting Keycloak (scripts/keycloak.sh)"
  "$HERE/keycloak.sh" >"$STATE/keycloak.log" 2>&1 &
  started keycloak $!
  wait_http "$ISSUER/.well-known/openid-configuration" 180 Keycloak
fi
# The realm is imported once; a realm older than the JSON lacks what the apps now require.
aud="$(token prudence-spa alice | cut -d. -f2 | python3 -I -c '
import sys,base64,json; s=sys.stdin.read().strip(); s+="="*(-len(s)%4)
print(json.loads(base64.urlsafe_b64decode(s)).get("aud"))' 2>/dev/null || true)"
case "$aud" in *prudence-api*) ;; *)
  die "realm chestertown is older than scripts/chestertown-realm.json (alice's token has no prudence-api audience); recreate the realm" ;;
esac

# ── infrastructure: Kafka, OPA ─────────────────────────────────────────────────────────────────
start_kafka() {
  "$KAFKA_BIN/kafka-server-start" "$STATE/kafka.properties" >>"$STATE/kafka.log" 2>&1 &
  started kafka $!
  for _ in $(seq 1 60); do
    "$KAFKA_BIN/kafka-topics" --bootstrap-server 127.0.0.1:$K_PORT --list >/dev/null 2>&1 && return 0; sleep 1
  done
  die "Kafka did not come up"
}
sed -e "s|^log.dirs=.*|log.dirs=$STATE/kafka-data|" \
    -e "s|^listeners=.*|listeners=PLAINTEXT://127.0.0.1:$K_PORT,CONTROLLER://127.0.0.1:$KC_PORT|" \
    -e "s|^advertised.listeners=.*|advertised.listeners=PLAINTEXT://127.0.0.1:$K_PORT,CONTROLLER://127.0.0.1:$KC_PORT|" \
    -e "s|^controller.quorum.bootstrap.servers=.*|controller.quorum.bootstrap.servers=127.0.0.1:$KC_PORT|" \
    "$(brew --prefix)/etc/kafka/server.properties" >"$STATE/kafka.properties"
"$KAFKA_BIN/kafka-storage" format --standalone -t "$("$KAFKA_BIN/kafka-storage" random-uuid)" \
  -c "$STATE/kafka.properties" >/dev/null
log "Kafka :$K_PORT"
start_kafka
"$KAFKA_BIN/kafka-topics" --bootstrap-server 127.0.0.1:$K_PORT --create --topic aos.executions \
  --partitions 2 --replication-factor 1 >/dev/null

log "OPA :$OPA_PORT"
opa run --server --addr "127.0.0.1:$OPA_PORT" "$REPO/policy/opa" >"$STATE/opa.log" 2>&1 &
started opa $!
wait_http "http://127.0.0.1:$OPA_PORT/health" 30 OPA

# ── Ernest: backend (OIDC + key) and its Kafka consumer ────────────────────────────────────────
start_ernest() {
  (cd "$ERNEST/backend" && EVIDENCE_BATCHING=on ERNEST_API_KEY=$ERNEST_KEY ERNEST_READ_API_KEY= \
    ANCHOR_PROVIDER= INFURA_URL= PRIVATE_KEY= CONTRACT_ADDRESS= WEBHOOK_URL= MLFLOW_TRACKING_URI= \
    CORS_ORIGIN='*' OIDC_ISSUER="$ISSUER" OIDC_AUDIENCE=ernest-spa OIDC_ROLE_CLAIM=roles \
    OIDC_ROLE_MAP=ernest-editor=read-write,ernest-reader=read-only \
    MONGODB_URI="mongodb://127.0.0.1:27017/$MONGO_DB" PORT=$E_PORT exec node dist/main.js) \
    >>"$STATE/ernest.log" 2>&1 &
  started ernest $!
  wait_http "http://127.0.0.1:$E_PORT/health" 60 Ernest
}
if [ ! -f "$ERNEST/backend/dist/main.js" ]; then
  log "building Ernest backend"
  (cd "$ERNEST" && npx -y pnpm@11.7.0 --filter ernest-backend run build >"$STATE/ernest-build.log" 2>&1)
fi
log "Ernest :$E_PORT (database $MONGO_DB)"
start_ernest
(cd "$ERNEST/event-ingestor" && go build -o "$STATE/ernest-ingestor" ./cmd/event-ingestor)
MONGODB_URI="mongodb://127.0.0.1:27017/$MONGO_DB" MONGO_DB_NAME=$MONGO_DB EVIDENCE_BATCHING=on \
  KAFKA_BROKERS=127.0.0.1:$K_PORT KAFKA_TOPICS=aos.executions KAFKA_GROUP=ernest-e2e \
  METRICS_PORT=$METRICS_PORT "$STATE/ernest-ingestor" kafka >"$STATE/ingestor.log" 2>&1 &
started ingestor $!

# ── Prudence, secured (JWT, PostgreSQL, production start checks) ───────────────────────────────
"$PSQL" -h 127.0.0.1 -d postgres -qc "drop database if exists $PG_DB" -c "drop role if exists $PG_DB" \
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

# ── AOS: mocks, deterministic model, the runtime ───────────────────────────────────────────────
(cd "$AOS" && go build -o "$STATE/aos" ./cmd/aos && go build -o "$STATE/aos-mocks" ./cmd/mocks \
  && go build -o "$STATE/aos-mock-llm" ./cmd/mock-ollama)
(cd "$AOS" && AOS_MOCK_PORT=$MOCK_PORT exec "$STATE/aos-mocks") >"$STATE/mocks.log" 2>&1 &
started mocks $!
(cd "$AOS" && MOCK_OLLAMA_PORT=$LLM_PORT exec "$STATE/aos-mock-llm") >"$STATE/llm.log" 2>&1 &
started llm $!
log "AOS :$A_PORT"
# Explicit empty values win over the AOS checkout's own .env (it is loaded but never overrides).
(cd "$AOS" && AOS_WORKSPACE_ROOT="$AOS" APP_ENV=dev AOS_STATE_STORE=memory ALLOW_LOCAL_TOOLS=false \
  AOS_TOOL_INTERNAL_HOSTS=127.0.0.1 AOS_IAM_URL= QDRANT_URL= REDIS_ADDR= REDIS_PASSWORD= \
  AOS_MOCK_BASE_URL="http://127.0.0.1:$MOCK_PORT" AOS_CALLBACK_BASE_URL="http://127.0.0.1:$A_PORT" \
  AUDIT_LOG_PATH="$STATE/aos-audit.jsonl" \
  LLM_ENGINE=ollama OLLAMA_BASE_URL="http://127.0.0.1:$LLM_PORT" LLM_MODEL=aos-demo:deterministic \
  OLLAMA_INTENT_MODEL=aos-demo:deterministic \
  AOS_JWT_ISSUER="$ISSUER" AOS_JWK_URL="$ISSUER/protocol/openid-connect/certs" \
  AOS_ROLE_MAP=aos-admin=admin,aos-operator=operator,aos-auditor=guest \
  PRUDENCE_PDP_URL="http://127.0.0.1:$P_PORT" PRUDENCE_PDP_TOKEN_URL="$TOKEN_URL" \
  PRUDENCE_PDP_CLIENT_ID=pdp-client PRUDENCE_PDP_CLIENT_SECRET=pdp-dev-secret PRUDENCE_PDP_SCOPE=pdp.decide \
  AOS_EVIDENCE_KAFKA_BROKERS=127.0.0.1:$K_PORT AOS_EVIDENCE_KAFKA_TOPIC=aos.executions \
  exec "$STATE/aos" -port $A_PORT) >"$STATE/aos.log" 2>&1 &
started aos $!
wait_http "http://127.0.0.1:$A_PORT/health/ready" 60 AOS

# ── helpers for the cases ──────────────────────────────────────────────────────────────────────
SUPPORT="$AOS_PRUDENCE_DEMO_SYSTEM_ID"
AOS_TOKEN="$(token aos-spa alice)"

ask() {  # ask MESSAGE → prints "status decision reason error" of the finished task
  local body id task
  body="$(python3 -I -c 'import json,sys; print(json.dumps({"lang":"en","message":sys.argv[1]}))' "$1")"
  AOS_TOKEN="$(token aos-spa alice)"
  id="$(curl -s -X POST "http://127.0.0.1:$A_PORT/ask" -H "Authorization: Bearer $AOS_TOKEN" \
    -H 'Content-Type: application/json' -d "$body" | json 'd.get("id","")')"
  for _ in $(seq 1 40); do
    task="$(curl -s -H "Authorization: Bearer $AOS_TOKEN" "http://127.0.0.1:$A_PORT/task?id=$id")"
    echo "$task" | grep -qE '"status":"(completed|error|governance_blocked|timeout)"' && break
    sleep 1
  done
  # A blocked run carries the verdict in data (data._governance on some paths).
  echo "$task" | json '" ".join(str(x) for x in (lambda g: [d.get("status"), g.get("decision"), str(g.get("reason") or "").replace(" ","_"), str(d.get("error") or "").replace(" ","_")[:80]])((d.get("data") or {}).get("_governance") or d.get("data") or {}))' 2>/dev/null || echo "no-result"
}
is() { [[ "$1" == $2 ]] && echo true || echo false; }  # is VALUE GLOB → true/false
SUPPORT_MSG="Run the governed customer-support lookup demo"

executions() {  # executions Ernest has recorded for the support system
  curl -s -H "X-Ernest-Api-Key: $ERNEST_KEY" "http://127.0.0.1:$E_PORT/api/stats/executions?modelId=$SUPPORT" \
    | json "d['counts'].get('$SUPPORT',0)" 2>/dev/null || echo -1
}

posture() {  # posture FIELD-EXPRESSION
  curl -s -H "Authorization: Bearer $(token prudence-spa alice)" "http://127.0.0.1:$P_PORT/api/posture" | json "$1"
}

wait_for() {  # wait_for SECONDS COMMAND... → true once the command succeeds
  local n="$1"; shift
  for _ in $(seq 1 "$n"); do "$@" && return 0; sleep 1; done
  return 1
}

# ── cases ──────────────────────────────────────────────────────────────────────────────────────
echo "running the cases"
set +e  # a failing check is a FAIL row, not the end of the run

# Identity: tokens carry what the apps require; Prudence's roles hold.
claims="$(token prudence-spa alice | cut -d. -f2 | python3 -I -c '
import sys,base64,json; s=sys.stdin.read().strip(); s+="="*(-len(s)%4); d=json.loads(base64.urlsafe_b64decode(s))
print("prudence-api" in (d.get("aud") if isinstance(d.get("aud"),list) else [d.get("aud")]), "prudence-author" in d.get("roles",[]))')"
record identity "alice's token: audience and suite roles" "$([ "$claims" = "True True" ] && echo true || echo false)" "$claims"
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$P_PORT/api/registry/systems" \
  -H 'Content-Type: application/json' -d '{}')"
record identity "Prudence without a token" "$([ "$code" = 401 ] && echo true || echo false)" "HTTP $code"
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$P_PORT/api/registry/systems" \
  -H "Authorization: Bearer $(token prudence-spa auditor)" -H 'Content-Type: application/json' -d '{}')"
record identity "auditor (read-only) cannot register a system" "$([ "$code" = 403 ] && echo true || echo false)" "HTTP $code"

# Governance verdicts for the four MVP systems, as AOS gets them.
check_verdict() {  # check_verdict CASE MESSAGE EXPECTED-DECISION
  local r; r="$(ask "$2")"
  record governance "$1" "$(is "$r" "governance_blocked $3 *")" "$r"
}
check_verdict "support (under assessment) → DENY" "$SUPPORT_MSG" DENY
check_verdict "credit (high risk, pending) → APPROVAL_REQUIRED" "Pre-screen the consumer credit application APP-1001" APPROVAL_REQUIRED
check_verdict "social scoring (prohibited) → DENY" "Run the social scoring for citizen CIT-77" DENY
check_verdict "translator (suspended) → DENY" 'Translate "hola" into English' DENY

# Approve the support system in Prudence (author submits, a reviewer approves): AOS may now run it.
curl -sf -o /dev/null -X POST -H "Authorization: Bearer $(token prudence-spa alice)" \
  "http://127.0.0.1:$P_PORT/api/registry/systems/$SUPPORT/submit"
curl -sf -o /dev/null -X POST -H "Authorization: Bearer $(token prudence-spa bob)" -H 'Content-Type: application/json' \
  -d '{"comment":"e2e"}' "http://127.0.0.1:$P_PORT/api/registry/systems/$SUPPORT/approve"
r="$(ask "$SUPPORT_MSG")"
record governance "support after approval → ALLOW, runs" "$(is "$r" "completed*")" "$r"

# Evidence: the execution reaches Ernest over Kafka; Prudence sees the system green.
has_executions() { [ "$(executions)" -ge "$1" ]; }
ok=false; wait_for 60 has_executions 1 && ok=true
record evidence "AOS → Kafka → Ernest: execution recorded" $ok "executions=$(executions)"
green() { [ "$(posture "[s['status'] for s in d['systems'] if s['id']=='$SUPPORT'][0]")" = GREEN ]; }
ok=false; wait_for 60 green && ok=true
record evidence "Prudence posture: support system GREEN" $ok "$(posture "[s['detail'] for s in d['systems'] if s['id']=='$SUPPORT'][0]" | cut -c1-60)"

# Kafka down: AOS still runs (evidence is asynchronous); the backlog arrives once Kafka is back.
before="$(executions)"
stop kafka
r="$(ask "$SUPPORT_MSG")"
record failure "Kafka down: the allowed run still completes" "$(is "$r" "completed*")" "$r"
start_kafka
ok=false; wait_for 120 has_executions $((before + 1)) && ok=true
record failure "Kafka back: the pending evidence arrives" $ok "executions $before → $(executions)"

# Ernest down: Prudence still decides and reports Ernest unreachable; healthy again once it is back.
stop ernest
r="$(ask "$SUPPORT_MSG")"
record failure "Ernest down: the allowed run still completes" "$(is "$r" "completed*")" "$r"
h="$(posture 'd.get("evidenceHealthy")')"
record failure "Ernest down: Prudence reports it unhealthy" "$([ "$h" = False ] && echo true || echo false)" "evidenceHealthy=$h"
start_ernest
healthy() { [ "$(posture 'd.get("evidenceHealthy")')" = True ]; }
ok=false; wait_for 120 healthy && ok=true
record failure "Ernest back: Prudence reports it healthy" $ok "evidenceHealthy=$(posture 'd.get("evidenceHealthy")')"

# Prudence down: AOS must not run a governed pipeline (fail closed).
stop prudence
r="$(ask "$SUPPORT_MSG")"
record failure "Prudence down: AOS fails closed" "$(is "$r" "error*fail-closed*")" "$r"

# ── report ─────────────────────────────────────────────────────────────────────────────────────
echo
printf '%-12s %-52s %-5s %s\n' Group Case "" Detail
printf '%s\n' "${RESULTS[@]}"
echo
total=${#RESULTS[@]}
echo "$((total - FAILED))/$total passed"
[ "$FAILED" -eq 0 ]
