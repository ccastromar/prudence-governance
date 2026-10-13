#!/usr/bin/env bash
# Local load test of Prudence + Ernest. Starts a throwaway stack (PostgreSQL, MongoDB, Redis,
# Ernest backend, Ernest event-ingestor, a fake OPA, Prudence) on non-default ports, runs the
# scenarios below and writes results.txt. Nothing outside $WORK is touched, and Ernest's
# external integrations (EVM anchoring, webhooks, MLflow) are forced off — the test must never
# reach the internet.
#
# Everything runs on ONE machine, so absolute numbers are indicative; what matters is the shape:
# where each component saturates and how reads grow with the chain.
#
#   ERNEST_REPO=/path/to/ai-blockchain-provenance ./loadtest/run-local.sh
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/.." && pwd)"
ERNEST_REPO="${ERNEST_REPO:-$REPO/../ai-blockchain-provenance}"
PG="${PG_BIN:-$(pg_config --bindir 2>/dev/null)}"
WORK="${WORK:-$(mktemp -d)}"; mkdir -p "$WORK"
STEP="${STEP_SECONDS:-15}"            # seconds per load step
PIDS=()
cleanup(){ for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; LC_ALL=C "$PG/pg_ctl" -D "$WORK/pg" -m fast stop >/dev/null 2>&1; wait 2>/dev/null; }
trap cleanup EXIT
log(){ echo "$*" | tee -a "$WORK/results.txt"; }
blocks(){ mongosh --quiet --port 27117 ernest --eval 'db.provenanceblocks.countDocuments()'; }
pending(){ PGPASSWORD=pw "$PG/psql" -h 127.0.0.1 -p 54329 -U prudence -d prudence -At -c "select count(*) from evidence_outbox where delivered_at is null and dead_at is null"; }
timed(){ curl -s -o /dev/null -w "%{time_total}s[%{http_code}]" -H "X-Ernest-Api-Key: ernest-key" "$1"; }
wait_drained(){ local t0 last=-1 stable=0 n; t0=$(date +%s); while :; do n=$(blocks); [ "$n" = "$last" ] && stable=$((stable+1)) || stable=0; last=$n; [ $stable -ge 3 ] && break; [ $(( $(date +%s) - t0 )) -gt 900 ] && break; sleep 2; done; echo "$last $(( $(date +%s) - t0 - 6 ))"; }
H64=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855

echo "work dir: $WORK"; : > "$WORK/results.txt"
(cd "$HERE" && go build -o "$WORK/loadgen" .) || exit 1
(cd "$REPO" && mvn -q compile && mvn -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeScope=runtime) || exit 1
(cd "$ERNEST_REPO/event-ingestor" && go build -o "$WORK/ingestor" ./cmd/event-ingestor) || exit 1
LG="$WORK/loadgen"

# ---------------------------------------------------------------- infrastructure
LC_ALL=C "$PG/initdb" -D "$WORK/pg" -U postgres --auth=trust >/dev/null
LC_ALL=C "$PG/pg_ctl" -D "$WORK/pg" -o "-p 54329 -c unix_socket_directories='' -c max_connections=200" -l "$WORK/pg.log" start >/dev/null; sleep 2
"$PG/psql" -h 127.0.0.1 -p 54329 -U postgres -q -c "create user prudence password 'pw'" -c "create database prudence owner prudence"
mkdir -p "$WORK/mongo"; mongod --dbpath "$WORK/mongo" --port 27117 --bind_ip 127.0.0.1 --quiet > "$WORK/mongo.log" 2>&1 & PIDS+=($!)
redis-server --port 6390 --bind 127.0.0.1 --save "" --appendonly no > "$WORK/redis.log" 2>&1 & PIDS+=($!)
"$LG" fake-opa -addr 127.0.0.1:18181 > "$WORK/opa.log" 2>&1 & PIDS+=($!)
sleep 2
# Empty values win over Ernest's own .env (dotenv never overrides a set variable): no EVM
# anchoring (Infura), no webhooks, no MLflow — nothing leaves this machine.
(cd "$ERNEST_REPO/backend" && INFURA_URL= PRIVATE_KEY= CONTRACT_ADDRESS= ANCHOR_PROVIDER= WEBHOOK_URL= MLFLOW_TRACKING_URI= \
   ANCHOR_QTS_ENABLED=false ERNEST_API_KEY=ernest-key ERNEST_READ_API_KEY=ernest-key PORT=3101 \
   MONGODB_URI=mongodb://127.0.0.1:27117/ernest NODE_ENV=development exec node dist/main.js > "$WORK/ernest.log" 2>&1) & ERNEST=$!; PIDS+=($ERNEST)
for i in $(seq 1 60); do curl -s -o /dev/null -m 2 http://127.0.0.1:3101/api/health && break; sleep 1; done
export REDIS_ADDR=127.0.0.1:6390 MONGODB_URI=mongodb://127.0.0.1:27117/ernest MONGO_DB_NAME=ernest \
       EVENT_INGESTOR_API_KEY=ingest-key WORKER_BLOCK_MS=200 RATE_LIMIT_RPS=1000000 RATE_LIMIT_BURST=1000000
PORT=3111 METRICS_PORT=9112 "$WORK/ingestor" serve > "$WORK/ingestor.log" 2>&1 & PIDS+=($!)
PORT=3112 METRICS_PORT=9113 "$WORK/ingestor" worker > "$WORK/worker.log" 2>&1 & WORKER=$!; PIDS+=($WORKER)

start_prudence(){ # $1 = label, rest = extra args
  local label=$1; shift
  java -Xmx2g -cp "$REPO/target/classes:$(cat "$WORK/cp.txt")" com.prudence.PrudenceApplication --spring.profiles.active=dev \
    --server.port=18099 --server.tomcat.threads.max=200 \
    --spring.datasource.url=jdbc:postgresql://127.0.0.1:54329/prudence --spring.datasource.username=prudence --spring.datasource.password=pw \
    --spring.datasource.hikari.maximum-pool-size=40 --spring.flyway.enabled=true --spring.jpa.hibernate.ddl-auto=validate \
    --prudence.opa.base-url=http://127.0.0.1:18181 --prudence.ernest.base-url=http://127.0.0.1:3101 --prudence.ernest.api-key=ernest-key \
    --prudence.alerts.drift-initial-delay-ms=3600000 --logging.level.com.prudence=WARN "$@" > "$WORK/prudence-$label.log" 2>&1 &
  PRUDENCE=$!; PIDS+=($PRUDENCE)
  for i in $(seq 1 90); do curl -s -m 2 -f -o /dev/null http://127.0.0.1:18099/actuator/health && return 0
    kill -0 $PRUDENCE 2>/dev/null || break; sleep 1; done
  echo "Prudence did not start:"; grep -m5 -E "Caused by|ERROR" "$WORK/prudence-$label.log"; exit 1
}
drain(){ # $1 = label: decisions leaving the outbox in 60 s
  local p0 p1; p0=$(pending); sleep 60; p1=$(pending)
  log "$1: $(( p0 - p1 )) delivered in 60s = $(python3 -c "print(round(($p0-$p1)/60,1))")/s · dead-lettered so far: $(PGPASSWORD=pw "$PG/psql" -h 127.0.0.1 -p 54329 -U prudence -d prudence -At -c 'select count(*) from evidence_outbox where dead_at is not null')"
}
start_prudence default --prudence.evidence.outbox-delay-ms=30000
B=http://127.0.0.1:18099/api
SYS=$(curl -s -X POST $B/registry/systems -H 'Content-Type: application/json' -d '{"name":"Credit scoring","intendedPurpose":"x","ownerName":"Owner","ownerEmail":"o@bank.eu","riskTier":"LIMITED","regulatoryRole":"DEPLOYER","systemVersion":"1.0","modelHash":"abc","annexI":false,"annexIII":false,"operatingGeography":"EU","affectedPopulation":"x","classificationJustification":"y","obligations":["Art 50"]}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
curl -s -o /dev/null -X POST $B/registry/systems/$SYS/submit; curl -s -o /dev/null -X POST $B/registry/systems/$SYS/approve
curl -s -o /dev/null -H "X-Ernest-Api-Key: ernest-key" -H 'Content-Type: application/json' -X POST http://127.0.0.1:3101/api/models \
  -d '{"modelId":"loadtest-model","modelName":"Load test","version":"1","mlflow":{"modelHash":"'$H64'","gitCommit":"0000000"}}'
log "machine: $(sysctl -n hw.ncpu) cores, $(( $(sysctl -n hw.memsize) / 1073741824 )) GB · all components on one host · ${STEP}s per step"

# ---------------------------------------------------------------- 1. PDP
log ""; log "## 1. PDP decisions (registry lookup + OPA hop + outbox insert)"
DEC='{"subject":"alice@bank.eu","pipeline":"credit","aiSystemId":"'$SYS'","action":"run"}'
for c in 1 8 32 64; do "$LG" http -url $B/policy/decisions -c $c -d ${STEP}s -body "$DEC" -label "PDP" | tee -a "$WORK/results.txt"; done
log "outbox backlog after the PDP steps: $(pending) undelivered decisions"

# ---------------------------------------------------------------- 2. outbox drain
log ""; log "## 2. Prudence outbox drain (decisions delivered per second)"
drain "direct, outbox-delay-ms=30000 (default)"
kill $PRUDENCE; wait $PRUDENCE 2>/dev/null; start_prudence fast --prudence.evidence.outbox-delay-ms=100
drain "direct, outbox-delay-ms=100"
kill $PRUDENCE; wait $PRUDENCE 2>/dev/null
start_prudence ingestor --prudence.evidence.outbox-delay-ms=100 --prudence.ernest.decision-transport=ingestor \
  --prudence.ernest.ingestor-url=http://127.0.0.1:3111 --prudence.ernest.ingestor-key=ingest-key
drain "ingestor transport, outbox-delay-ms=100"
kill $PRUDENCE; wait $PRUDENCE 2>/dev/null; start_prudence quiet --prudence.evidence.outbox-delay-ms=3600000

# ---------------------------------------------------------------- 3. Ernest direct appends
log ""; log "## 3. Ernest direct appends (POST /api/inferences) — what one client IP gets"
sleep 65   # let Ernest's per-IP throttle window reset after the drain runs
INF='{"modelId":"loadtest-model","inferenceId":"{{uuid}}","inputHash":"'$H64'","outputHash":"'$H64'","metadata":{"stage":"execution","aiSystemId":"'$SYS'"}}'
"$LG" http -url http://127.0.0.1:3101/api/inferences -H "X-Ernest-Api-Key: ernest-key" -c 8 -d 60s -body "$INF" -label "Ernest append (60s)" | tee -a "$WORK/results.txt"
kill -0 $ERNEST 2>/dev/null && log "Ernest backend still up" || log "Ernest backend DIED"

# ---------------------------------------------------------------- 4/5. ingestor + chain growth
log ""; log "## 4. Raw append capacity of the chain (event-ingestor worker: same serialized algorithm, no HTTP throttle)"
EV='{"modelId":"loadtest-model","inferenceId":"{{uuid}}","inputHash":"'$H64'","outputHash":"'$H64'","metadata":{"stage":"execution","aiSystemId":"'$SYS'"}}'
ingest(){ "$LG" http -url http://127.0.0.1:3111/events -H "X-Ernest-Ingest-Key: ingest-key" -H "X-Ernest-Event-Type: inference.logged" \
  -H "X-Ernest-Event-Source: loadtest" -H "X-Ernest-Source-Event-Id: {{uuid}}" -c "$1" -n "$2" -body "$EV" -label "$3"; }
for chunk in 1 2 3 4 5; do
  b0=$(blocks); ingest 16 8000 "ingestor accept 8000 (chunk $chunk)" | tee -a "$WORK/results.txt"
  read n secs <<<"$(wait_drained)"; log "   worker appended $(( n - b0 )) of 8000 in ~${secs}s = $(python3 -c "print(round(($n-$b0)/max(1,$secs),1))")/s · chain now $n blocks"
done

reads(){
  sleep 65   # fresh throttle window, so the timings are of real reads, not of 429s
  log "chain = $(blocks) blocks: verify=$(timed http://127.0.0.1:3101/api/verify) · model integrity (loadtest-model)=$(timed http://127.0.0.1:3101/api/models/loadtest-model/integrity) · Prudence posture=$(curl -s -o /dev/null -w '%{time_total}s[%{http_code}]' $B/posture) · drift=$(curl -s -o /dev/null -w '%{time_total}s[%{http_code}]' $B/posture/drift)"
}
log ""; log "## 5. Reads that scan the chain (single call each, fresh throttle window)"
reads

log ""; log "## 6. Ingestor stream trimming (REDIS_STREAM_MAXLEN=10000)"
b0=$(blocks); ingest 32 20000 "ingestor accept 20000, worker running" | tee -a "$WORK/results.txt"
read n secs <<<"$(wait_drained)"; log "   reached the chain: $(( n - b0 )) of 20000 accepted"
kill $WORKER; wait $WORKER 2>/dev/null
b0=$(blocks); ingest 32 15000 "ingestor accept 15000, worker stopped" | tee -a "$WORK/results.txt"
log "   stream length while the worker is down: $(redis-cli -p 6390 XLEN ernest:events:incoming)"
PORT=3112 METRICS_PORT=9113 "$WORK/ingestor" worker > "$WORK/worker2.log" 2>&1 & PIDS+=($!)
read n secs <<<"$(wait_drained)"; log "   after the worker restarted: $(( n - b0 )) of 15000 accepted events reached the chain"

log ""; log "## 5b. Reads, bigger chain"
reads
kill -0 $ERNEST 2>/dev/null && log "Ernest backend still up at the end" || log "Ernest backend DIED"
log ""; log "full logs: $WORK"
