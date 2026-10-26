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
# go, node, mvn (JDK 25+), opa, mongosh, psql and Kafka: KAFKA_HOME (an unpacked Apache Kafka
# 4.x release), or `brew install kafka`. CI runs it nightly (.github/workflows/e2e.yml).
# Observability: the apps send traces and metrics to scripts/otlp-sink (:14318), a local OTLP
# receiver, and AOS logs JSON; the observability cases follow one allowed run from its log line
# to its trace across AOS and Prudence, the correlation id into Ernest's evidence, and the
# asynchronous hops (outbox, Kafka) as linked spans (contract/observability).
# Not covered: the consoles (browser sign-in/sign-out), Docker images, a real LLM, the
# collector/Tempo/Grafana themselves (compose.observability.yaml).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
AOS="$REPO/../aos-agentic-orchestration-system"
ERNEST="$REPO/../ai-blockchain-provenance"
ISSUER="http://localhost:8081/realms/chestertown"
TOKEN_URL="$ISSUER/protocol/openid-connect/token"
P_PORT=18099 OPA_PORT=18181 E_PORT=13001 A_PORT=18080 K_PORT=19092 KC_PORT=19093
MOCK_PORT=19000 LLM_PORT=19435 METRICS_PORT=19102 OTLP_PORT=14318
PG_DB=prudence_e2e PG_PASSWORD=prudence-e2e MONGO_DB=ernest_e2e
ERNEST_KEY=e2e-write-key
STATE="$(mktemp -d "${TMPDIR:-/tmp}/chestertown-e2e.XXXXXX")"
PSQL="$(command -v psql || echo "$(pg_config --bindir 2>/dev/null)/psql")"
# Apache's layout (bin/*.sh, config/server.properties), which Homebrew keeps under libexec.
KAFKA_HOME="${KAFKA_HOME:-$(brew --prefix kafka 2>/dev/null)/libexec}"
kafka() { local tool="$1"; shift; "$KAFKA_HOME/bin/$tool.sh" "$@"; }

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
[ -x "$KAFKA_HOME/bin/kafka-server-start.sh" ] || die "Kafka not found (set KAFKA_HOME, or brew install kafka)"
[ -d "$AOS/cmd/aos" ] || die "AOS checkout not found at $AOS"
[ -d "$ERNEST/backend" ] || die "Ernest checkout not found at $ERNEST"
for port in $P_PORT $OPA_PORT $E_PORT $A_PORT $K_PORT $KC_PORT $MOCK_PORT $LLM_PORT $METRICS_PORT $OTLP_PORT; do
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

# ── observability: a local OTLP receiver every app exports to ──────────────────────────────────
(cd "$HERE/otlp-sink" && go build -o "$STATE/otlp-sink" .)
"$STATE/otlp-sink" -addr "127.0.0.1:$OTLP_PORT" -dir "$STATE/otlp" >"$STATE/otlp-sink.log" 2>&1 &
started otlp-sink $!
wait_http "http://127.0.0.1:$OTLP_PORT/health" 30 otlp-sink
log "OTLP receiver :$OTLP_PORT"
# AOS, Ernest's backend and its ingestor read the standard variables; Prudence its own below.
export OTEL_EXPORTER_OTLP_ENDPOINT="http://127.0.0.1:$OTLP_PORT"
export OTEL_RESOURCE_ATTRIBUTES="deployment.environment.name=e2e" OTEL_METRIC_EXPORT_INTERVAL=5000
export OTEL_BSP_SCHEDULE_DELAY=1000

# ── infrastructure: Kafka, OPA ─────────────────────────────────────────────────────────────────
start_kafka() {
  kafka kafka-server-start "$STATE/kafka.properties" >>"$STATE/kafka.log" 2>&1 &
  started kafka $!
  for _ in $(seq 1 60); do
    kafka kafka-topics --bootstrap-server 127.0.0.1:$K_PORT --list >/dev/null 2>&1 && return 0; sleep 1
  done
  die "Kafka did not come up"
}
sed -e "s|^log.dirs=.*|log.dirs=$STATE/kafka-data|" \
    -e "s|^listeners=.*|listeners=PLAINTEXT://127.0.0.1:$K_PORT,CONTROLLER://127.0.0.1:$KC_PORT|" \
    -e "s|^advertised.listeners=.*|advertised.listeners=PLAINTEXT://127.0.0.1:$K_PORT,CONTROLLER://127.0.0.1:$KC_PORT|" \
    -e "s|^controller.quorum.bootstrap.servers=.*|controller.quorum.bootstrap.servers=127.0.0.1:$KC_PORT|" \
    "$KAFKA_HOME/config/server.properties" >"$STATE/kafka.properties"
kafka kafka-storage format --standalone -t "$(kafka kafka-storage random-uuid)" \
  -c "$STATE/kafka.properties" >/dev/null
log "Kafka :$K_PORT"
start_kafka
kafka kafka-topics --bootstrap-server 127.0.0.1:$K_PORT --create --topic aos.executions \
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
  PRUDENCE_EVIDENCE_PSEUDONYM_KEY=e2e-suite-only-pseudonym-key-0123456789abcdef \
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=prudence-api \
  PRUDENCE_IDENTITY_ROLE_MAPPING=prudence-reader=reader,prudence-author=author,prudence-system-approver=system-approver,prudence-policy-reviewer=policy-reviewer,prudence-admin=admin \
  PRUDENCE_DB_URL="jdbc:postgresql://127.0.0.1:5432/$PG_DB" PRUDENCE_DB_USER=$PG_DB PRUDENCE_DB_PASSWORD=$PG_PASSWORD \
  PRUDENCE_ERNEST_BASE_URL="http://127.0.0.1:$E_PORT" PRUDENCE_ERNEST_API_KEY=$ERNEST_KEY \
  SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT=1025 MANAGEMENT_HEALTH_MAIL_ENABLED=false \
  PRUDENCE_ALERTS_GOVERNANCE_TO=governance@example.com PRUDENCE_ALERTS_OPERATIONS_TO=ops@example.com \
  PRUDENCE_CORS_ORIGINS=http://localhost:5200 PRUDENCE_CONSOLE_URL=http://localhost:5200 \
  PRUDENCE_OTEL_ENABLED=true PRUDENCE_OTEL_ENDPOINT="http://127.0.0.1:$OTLP_PORT" PRUDENCE_TRACING_SAMPLING=1.0 \
  MANAGEMENT_OTLP_METRICS_EXPORT_STEP=5s OTEL_DEPLOYMENT_ENVIRONMENT=e2e \
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
(cd "$AOS" && AOS_WORKSPACE_ROOT="$AOS" APP_ENV=dev LOG_FORMAT=json AOS_STATE_STORE=memory ALLOW_LOCAL_TOOLS=false \
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
  echo "$id" >"$STATE/last-task"  # ask runs in a subshell: the observability cases read it here
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

ask_as() {  # ask_as USER MESSAGE: like ask, as another test user
  local body id task tok
  body="$(python3 -I -c 'import json,sys; print(json.dumps({"lang":"en","message":sys.argv[1]}))' "$2")"
  tok="$(token aos-spa "$1")"
  id="$(curl -s -X POST "http://127.0.0.1:$A_PORT/ask" -H "Authorization: Bearer $tok" \
    -H 'Content-Type: application/json' -d "$body" | json 'd.get("id","")')"
  for _ in $(seq 1 40); do
    task="$(curl -s -H "Authorization: Bearer $tok" "http://127.0.0.1:$A_PORT/task?id=$id")"
    echo "$task" | grep -qE '"status":"(completed|error|governance_blocked|timeout)"' && break
    sleep 1
  done
  echo "$task" | json '" ".join([str(d.get("status")), str(d.get("error") or "").replace(" ","_")[:80]])' 2>/dev/null || echo "no-result"
}

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

# Governance functions (contract v1): each app resolves the same token to its own functions.
fns="$(curl -s -H "Authorization: Bearer $(token prudence-spa alice)" "http://127.0.0.1:$P_PORT/api/me" | json '",".join(d.get("functions", []))')"
record identity "alice's functions in Prudence (/api/me)" "$([ "$fns" = "incident.handle,oversight,policy.author,system.approve,system.declare" ] && echo true || echo false)" "$fns"
# The Ernest console sends the ID token (its audience is ernest-spa), as Ernest expects.
idt="$(curl -sf "$TOKEN_URL" -d grant_type=password -d client_id=ernest-spa -d username=alice -d password=alice \
  -d scope=openid | json 'd["id_token"]')"
fns="$(curl -s -H "Authorization: Bearer $idt" "http://127.0.0.1:$E_PORT/api/auth/whoami" | json '",".join(d.get("functions", []))')"
record identity "alice's functions in Ernest (whoami)" "$([ "$fns" = "evidence.write,platform.admin" ] && echo true || echo false)" "$fns"

# Authorization by function (phase 3): each route needs the function the contract assigns it.
http() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
code="$(http -H "Authorization: Bearer $(token prudence-spa auditor)" "http://127.0.0.1:$P_PORT/api/policy-admin/status")"
record functions "Prudence: audit reads policies" "$([ "$code" = 200 ] && echo true || echo false)" "HTTP $code"
code="$(http -X POST -H "Authorization: Bearer $(token prudence-spa alice)" -H 'Content-Type: application/json' -d '{}' "http://127.0.0.1:$P_PORT/api/policy-admin/publications")"
record functions "Prudence: an author cannot publish (platform.admin)" "$([ "$code" = 403 ] && echo true || echo false)" "HTTP $code"
code="$(http -X POST -H "Authorization: Bearer $(token prudence-spa alice)" -H 'Content-Type: application/json' -d '{}' "http://127.0.0.1:$P_PORT/api/policy/decisions")"
record functions "Prudence: a person cannot call the PDP (runtime.decide)" "$([ "$code" = 403 ] && echo true || echo false)" "HTTP $code"
pdp_token="$(curl -sf "$TOKEN_URL" -d grant_type=client_credentials -d client_id=pdp-client -d client_secret=pdp-dev-secret \
  -d scope=pdp.decide | json 'd["access_token"]')"
code="$(http -X POST -H "Authorization: Bearer $pdp_token" -H 'Content-Type: application/json' -d '{}' "http://127.0.0.1:$P_PORT/api/policy/decisions")"
record functions "Prudence: a workload's bad request is a 400, not a 403" "$([ "$code" = 400 ] && echo true || echo false)" "HTTP $code"
code="$(http -X POST -H "Authorization: Bearer $pdp_token" -H 'Content-Type: application/json' \
  -d '{"subject":"e2e","pipeline":"e2e","aiSystemId":"not-registered-e2e","action":"invoke"}' \
  "http://127.0.0.1:$P_PORT/api/policy/decisions")"
record functions "Prudence: an unregistered system is a DENY (403), not a 500" "$([ "$code" = 403 ] && echo true || echo false)" "HTTP $code"
aud_idt="$(curl -sf "$TOKEN_URL" -d grant_type=password -d client_id=ernest-spa -d username=auditor -d password=auditor \
  -d scope=openid | json 'd["id_token"]')"
code="$(http -X POST -H "Authorization: Bearer $aud_idt" -H 'Content-Type: application/json' -d '{}' "http://127.0.0.1:$E_PORT/api/models")"
record functions "Ernest: audit cannot write evidence (evidence.write)" "$([ "$code" = 401 ] && echo true || echo false)" "HTTP $code"

# Governance verdicts for the four MVP systems, as AOS gets them.
check_verdict() {  # check_verdict CASE MESSAGE EXPECTED-DECISION
  local r; r="$(ask "$2")"
  record governance "$1" "$(is "$r" "governance_blocked $3 *")" "$r"
}
check_verdict "support (under assessment) → DENY" "$SUPPORT_MSG" DENY
check_verdict "credit (high risk, pending) → APPROVAL_REQUIRED" "Pre-screen the consumer credit application APP-1001" APPROVAL_REQUIRED
check_verdict "social scoring (prohibited) → DENY" "Run the social scoring for citizen CIT-77" DENY
check_verdict "translator (suspended) → DENY" 'Translate "hola" into English' DENY
# AOS: the support demo needs ai.use:support or :operator; the auditor holds only ai.use:guest.
r="$(ask_as auditor "$SUPPORT_MSG")"
record functions "AOS: the auditor cannot run the support demo (ai.use)" "$(is "$r" "error*rbac*")" "$r"

# Approve the support system in Prudence (author submits, a reviewer approves): AOS may now run it.
curl -sf -o /dev/null -X POST -H "Authorization: Bearer $(token prudence-spa alice)" \
  "http://127.0.0.1:$P_PORT/api/registry/systems/$SUPPORT/submit"
curl -sf -o /dev/null -X POST -H "Authorization: Bearer $(token prudence-spa bob)" -H 'Content-Type: application/json' \
  -d '{"comment":"e2e"}' "http://127.0.0.1:$P_PORT/api/registry/systems/$SUPPORT/approve"
r="$(ask "$SUPPORT_MSG")"
record governance "support after approval → ALLOW, runs" "$(is "$r" "completed*")" "$r"
ALLOWED_TASK="$(cat "$STATE/last-task")"

# Evidence: the execution reaches Ernest over Kafka; Prudence sees the system green.
has_executions() { [ "$(executions)" -ge "$1" ]; }
ok=false; wait_for 60 has_executions 1 && ok=true
record evidence "AOS → Kafka → Ernest: execution recorded" $ok "executions=$(executions)"
green() { [ "$(posture "[s['status'] for s in d['systems'] if s['id']=='$SUPPORT'][0]")" = GREEN ]; }
ok=false; wait_for 60 green && ok=true
record evidence "Prudence posture: support system GREEN" $ok "$(posture "[s['detail'] for s in d['systems'] if s['id']=='$SUPPORT'][0]" | cut -c1-60)"

# Observability (contract/observability): follow the allowed run from its log line to its trace,
# its flow into Ernest's evidence, and its asynchronous hops as linked spans.
SINK="http://127.0.0.1:$OTLP_PORT"
# A log line about the run carries the task as correlation_id and the trace it belongs to.
trace="$(python3 -I - "$STATE/aos.log" "$ALLOWED_TASK" <<'PY'
import json, sys
for raw in open(sys.argv[1], errors="replace"):
    try:
        line = json.loads(raw)
    except ValueError:
        continue
    if line.get("correlation_id") == sys.argv[2] and line.get("trace_id"):
        print(line["trace_id"]); break
PY
)"
record observability "AOS log line → its trace (correlation_id, trace_id)" "$([ -n "$trace" ] && echo true || echo false)" "task=${ALLOWED_TASK:0:8} trace=${trace:0:12}"
services() { curl -s "$SINK/spans?trace_id=$trace" | json '",".join(sorted({s["service"] for s in d}))'; }
crosses() { [[ "$(services)" == *aos*prudence* ]]; }
ok=false; [ -n "$trace" ] && wait_for 60 crosses && ok=true
record observability "the trace crosses AOS → Prudence" $ok "services=$(services)"
step() { [ "$(curl -s "$SINK/spans?trace_id=$trace&name=aos.step" | json 'len(d)')" -ge 1 ]; }
ok=false; [ -n "$trace" ] && wait_for 30 step && ok=true
record observability "the run and its steps are spans of it" $ok "aos.step spans=$(curl -s "$SINK/spans?trace_id=$trace&name=aos.step" | json 'len(d)')"
# Asynchronous hops are new spans linked to the run's trace, never its children.
linked() { curl -s "$SINK/spans?linked_trace_id=$trace" | json '",".join(sorted({s["name"] for s in d if s["trace_id"] != "'"$trace"'"}))'; }
has_link() { [[ "$(linked)" == *"$1"* ]]; }
for hop in aos.execution.report prudence.evidence.deliver ernest.kafka.process; do
  ok=false; [ -n "$trace" ] && wait_for 60 has_link "$hop" && ok=true
  record observability "async hop linked, not a child: $hop" $ok "$(linked | cut -c1-60)"
done
# The flow reaches Ernest: AOS's execution (source aos) and Prudence's decision (an inference,
# source api/<system>) both carry the task.
flow() {
  mongosh --quiet "mongodb://127.0.0.1:27017/$MONGO_DB" --eval \
    "db.evidence_events.distinct('source', {'data.metadata.correlationId': '$ALLOWED_TASK'}).sort().join(',')" 2>/dev/null
}
in_ernest() { [[ "$(flow)" == *aos* && "$(flow)" == *api/* ]]; }
ok=false; wait_for 60 in_ernest && ok=true
record observability "correlation_id in Ernest: AOS run and Prudence decision" $ok "sources=$(flow)"
# Metrics: histograms with the objectives' buckets, from each app.
bounds() { curl -s "$SINK/metrics?name=$1" | json "next((p['bounds'] for p in d if p['type']=='histogram' and $2), [])"; }
has_bucket() { [[ "$(bounds "$1" "$2")" == *"$3"* ]]; }
ok=false; wait_for 60 has_bucket prudence.decision.duration "p['attributes'].get('verdict')=='ALLOW'" 0.25 && ok=true
record observability "Prudence: decision histogram with the 0.25 s bucket" $ok "$(bounds prudence.decision.duration "p['attributes'].get('verdict')=='ALLOW'" | cut -c1-50)"
ok=false; wait_for 90 has_bucket ernest.evidence.chaining.delay True 900 && ok=true
record observability "Ernest: chaining delay histogram" $ok "$(bounds ernest.evidence.chaining.delay True | cut -c1-50)"
# From a metric to its trace: a histogram's exemplar names a trace the sink holds spans of.
known() { [ -n "$1" ] && [ "$(curl -s "$SINK/spans?trace_id=$1" | json 'len(d)')" -ge 1 ]; }
prudence_exemplar() { curl -s "$SINK/metrics?name=prudence.decision.duration" | json 'next((e for p in d for e in p.get("exemplars") or []), "")'; }
ex_ok() { known "$(prudence_exemplar)"; }
ok=false; wait_for 60 ex_ok && ok=true
record observability "metric → trace: Prudence decision histogram exemplar" $ok "exemplar=$(prudence_exemplar | cut -c1-12)"
aos_exemplar() {
  curl -s -H 'Accept: application/openmetrics-text' "http://127.0.0.1:$A_PORT/metrics" \
    | sed -n 's/^aos_gate_duration_seconds_bucket{verdict="ALLOW".*# {trace_id="\([0-9a-f]*\)"}.*/\1/p' | head -1
}
aos_ex_ok() { known "$(aos_exemplar)"; }
ok=false; wait_for 30 aos_ex_ok && ok=true
record observability "metric → trace: AOS gate histogram exemplar" $ok "exemplar=$(aos_exemplar | cut -c1-12)"
gate="$(curl -s "http://127.0.0.1:$A_PORT/metrics" | grep -c 'aos_gate_duration_seconds_bucket{verdict="ALLOW",le="0.25"}')"
record observability "AOS: gate histogram with the 0.25 s bucket" "$([ "$gate" -ge 1 ] && echo true || echo false)" "series=$gate"

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
