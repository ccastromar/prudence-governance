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
# A PostgreSQL *server* install (initdb + postgres side by side). pg_config may belong to a
# client-only libpq, so look for the server binary itself; override with PG_BIN.
pg_server_bin(){ local d; for d in "${PG_BIN:-}" $(ls -d "$(brew --prefix 2>/dev/null)"/opt/postgresql@*/bin 2>/dev/null | sort -rV) "$(dirname "$(command -v postgres 2>/dev/null)")"; do [ -n "$d" ] && [ -x "$d/postgres" ] && [ -x "$d/initdb" ] && { echo "$d"; return; }; done; }
PG="$(pg_server_bin)"; [ -n "$PG" ] || { echo "no PostgreSQL server found (brew install postgresql@16, or set PG_BIN)" >&2; exit 1; }
WORK="${WORK:-$(mktemp -d)}"; mkdir -p "$WORK"
STEP="${STEP_SECONDS:-15}"            # seconds per load step
PIDS=()
cleanup(){ for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; LC_ALL=C "$PG/pg_ctl" -D "$WORK/pg" -m fast stop >/dev/null 2>&1; wait 2>/dev/null; }
trap cleanup EXIT
log(){ echo "$*" | tee -a "$WORK/results.txt"; }
# Evidence recorded on the chain: blocks, or (ADR-004 batching) chained events — with batching
# one block holds a whole batch, so counting blocks would understate throughput.
blocks(){ if [ "${EVIDENCE_BATCHING:-off}" = on ]; then mongosh --quiet --port 27117 ernest --eval 'db.evidence_events.countDocuments({state:"chained"})'; else mongosh --quiet --port 27117 ernest --eval 'db.provenanceblocks.countDocuments()'; fi; }
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
       EVENT_INGESTOR_API_KEY=ingest-key WORKER_BLOCK_MS=200 RATE_LIMIT_RPS=1000000 RATE_LIMIT_BURST=1000000 \
       REDIS_STREAM_MAX_BACKLOG=10000 WORKER_TRIM_INTERVAL_MS=2000
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
start_prudence default --prudence.evidence.outbox-delay-ms=3600000
B=http://127.0.0.1:18099/api
SYS=$(curl -s -X POST $B/registry/systems -H 'Content-Type: application/json' -d '{"name":"Credit scoring","intendedPurpose":"x","ownerName":"Owner","ownerEmail":"o@bank.eu","riskTier":"LIMITED","regulatoryRole":"DEPLOYER","systemVersion":"1.0","modelHash":"abc","annexI":false,"annexIII":false,"operatingGeography":"EU","affectedPopulation":"x","classificationJustification":"y","obligations":["Art 50"]}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
curl -s -o /dev/null -X POST $B/registry/systems/$SYS/submit; curl -s -o /dev/null -X POST $B/registry/systems/$SYS/approve
curl -s -o /dev/null -H "X-Ernest-Api-Key: ernest-key" -H 'Content-Type: application/json' -X POST http://127.0.0.1:3101/api/models \
  -d '{"modelId":"loadtest-model","modelName":"Load test","version":"1","mlflow":{"modelHash":"'$H64'","gitCommit":"0000000"}}'
log "machine: $(sysctl -n hw.ncpu) cores, $(( $(sysctl -n hw.memsize) / 1073741824 )) GB · all components on one host · ${STEP}s per step · EVIDENCE_BATCHING=${EVIDENCE_BATCHING:-off}"

# ---------------------------------------------------------------- 1. PDP
log ""; log "## 1. PDP decisions (registry lookup + OPA hop + outbox insert)"
DEC='{"subject":"alice@bank.eu","pipeline":"credit","aiSystemId":"'$SYS'","action":"run"}'
for c in 1 8 32 64; do "$LG" http -url $B/policy/decisions -c $c -d ${STEP}s -body "$DEC" -label "PDP" | tee -a "$WORK/results.txt"; done
log "outbox backlog after the PDP steps: $(pending) undelivered decisions"

# ---------------------------------------------------------------- 2. outbox drain
log ""; log "## 2. Prudence outbox drain (decisions delivered per second; chain growth alongside)"
drain2(){ # $1 = label: decisions leaving the outbox and blocks added to the chain in 60 s
  local p0 p1 b0 b1; p0=$(pending); b0=$(blocks); sleep 60; p1=$(pending); b1=$(blocks)
  log "$1: $(( p0 - p1 )) delivered in 60s = $(python3 -c "print(round(($p0-$p1)/60,1))")/s · chain +$(( b1 - b0 )) blocks = $(python3 -c "print(round(($b1-$b0)/60,1))")/s · dead: $(PGPASSWORD=pw "$PG/psql" -h 127.0.0.1 -p 54329 -U prudence -d prudence -At -c 'select count(*) from evidence_outbox where dead_at is not null')"
  [ "$p1" = 0 ] && log "   (backlog exhausted within the window: the rate above is a lower bound)"
}
kill $PRUDENCE; wait $PRUDENCE 2>/dev/null
start_prudence direct --prudence.evidence.outbox-delay-ms=2000
drain2 "direct transport, batched (200) + 8 concurrent senders"
kill $PRUDENCE; wait $PRUDENCE 2>/dev/null
start_prudence ingestor --prudence.evidence.outbox-delay-ms=2000 --prudence.ernest.decision-transport=ingestor \
  --prudence.ernest.ingestor-url=http://127.0.0.1:3111 --prudence.ernest.ingestor-key=ingest-key
drain2 "ingestor transport, 8 concurrent senders"
kill $PRUDENCE; wait $PRUDENCE 2>/dev/null; start_prudence quiet --prudence.evidence.outbox-delay-ms=3600000
read n secs <<<"$(wait_drained)"   # let the ingestor worker finish what it queued

# Kafka transport (Ernest ADR-005), when a broker is reachable (./scripts/kafka.sh): a throwaway
# topic per run, one `event-ingestor kafka` consumer. The outbox is refilled first so the 60 s
# window measures the transport, not an empty backlog.
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-127.0.0.1:9092}"
if command -v kafka-topics >/dev/null && kafka-topics --bootstrap-server "$KAFKA_BOOTSTRAP" --list >/dev/null 2>&1; then
  KTOPIC="loadtest.decisions.$(date +%s)"
  kafka-topics --bootstrap-server "$KAFKA_BOOTSTRAP" --create --topic "$KTOPIC" --partitions 8 --replication-factor 1 >/dev/null 2>&1
  KAFKA_BROKERS=$KAFKA_BOOTSTRAP KAFKA_TOPICS=$KTOPIC KAFKA_GROUP=$KTOPIC PORT=3113 METRICS_PORT=9114 \
    "$WORK/ingestor" kafka > "$WORK/kafka-source.log" 2>&1 & PIDS+=($!)
  "$LG" http -url $B/policy/decisions -c 64 -d $(( STEP * 5 ))s -body "$DEC" -label "refill outbox" > /dev/null
  log "outbox backlog before the kafka step: $(pending)"
  kill $PRUDENCE; wait $PRUDENCE 2>/dev/null
  start_prudence kafka --prudence.evidence.outbox-delay-ms=2000 --prudence.ernest.decision-transport=kafka \
    --prudence.ernest.kafka-bootstrap-servers=$KAFKA_BOOTSTRAP --prudence.ernest.kafka-topic=$KTOPIC
  drain2 "kafka transport (8 partitions, 1 consumer), 8 concurrent senders"
  kill $PRUDENCE; wait $PRUDENCE 2>/dev/null; start_prudence quiet --prudence.evidence.outbox-delay-ms=3600000
  read n secs <<<"$(wait_drained)"
  kafka-topics --bootstrap-server "$KAFKA_BOOTSTRAP" --delete --topic "$KTOPIC" >/dev/null 2>&1
else
  log "kafka transport: skipped (no broker at $KAFKA_BOOTSTRAP — run ./scripts/kafka.sh)"
fi

# ---------------------------------------------------------------- 3. Ernest direct appends
log ""; log "## 3. Ernest direct appends with a valid key (per-credential quota)"
INF='{"modelId":"loadtest-model","inferenceId":"{{uuid}}","inputHash":"'$H64'","outputHash":"'$H64'","metadata":{"stage":"execution","aiSystemId":"'$SYS'"}}'
b0=$(blocks)
"$LG" http -url http://127.0.0.1:3101/api/inferences -H "X-Ernest-Api-Key: ernest-key" -c 8 -d ${STEP}s -body "$INF" -label "single POST /api/inferences" | tee -a "$WORK/results.txt"
log "   chain +$(( $(blocks) - b0 )) blocks"
BATCH=$(python3 -c "import sys; print('{\"items\":[' + ','.join([sys.argv[1]]*200) + ']}')" "$INF")
b0=$(blocks)
"$LG" http -url http://127.0.0.1:3101/api/inferences/batch -H "X-Ernest-Api-Key: ernest-key" -c 4 -d ${STEP}s -body "$BATCH" -label "batch POST (200 items/request)" | tee -a "$WORK/results.txt"
log "   chain +$(( $(blocks) - b0 )) blocks in ${STEP}s = $(python3 -c "print(round(($(blocks)-$b0)/$STEP,1))") appends/s"
kill -0 $ERNEST 2>/dev/null && log "Ernest backend still up" || log "Ernest backend DIED"

# ---------------------------------------------------------------- 4/5. ingestor + chain growth
log ""; log "## 4. Raw append capacity of the chain (event-ingestor worker)"
EV='{"modelId":"loadtest-model","inferenceId":"{{uuid}}","inputHash":"'$H64'","outputHash":"'$H64'","metadata":{"stage":"execution","aiSystemId":"'$SYS'"}}'
ingest(){ "$LG" http -url http://127.0.0.1:3111/events -H "X-Ernest-Ingest-Key: ingest-key" -H "X-Ernest-Event-Type: inference.logged" \
  -H "X-Ernest-Event-Source: loadtest" -H "X-Ernest-Source-Event-Id: {{uuid}}" -c "$1" -n "$2" -body "$EV" -label "$3"; }
for chunk in 1 2 3; do
  b0=$(blocks); ingest 16 8000 "ingestor accept 8000 (chunk $chunk)" | tee -a "$WORK/results.txt"
  read n secs <<<"$(wait_drained)"; log "   worker appended $(( n - b0 )) of 8000 in ~${secs}s = $(python3 -c "print(round(($n-$b0)/max(1,$secs),1))")/s · chain now $n blocks"
done

reads(){
  log "chain = $(blocks) blocks: verify=$(timed http://127.0.0.1:3101/api/verify) (again: $(timed http://127.0.0.1:3101/api/verify)) · verify?mode=full=$(timed 'http://127.0.0.1:3101/api/verify?mode=full') · model integrity=$(timed http://127.0.0.1:3101/api/models/loadtest-model/integrity) · stats/executions=$(timed http://127.0.0.1:3101/api/stats/executions) · Prudence posture=$(curl -s -o /dev/null -w '%{time_total}s[%{http_code}]' $B/posture) · drift=$(curl -s -o /dev/null -w '%{time_total}s[%{http_code}]' $B/posture/drift) complete=$(curl -s $B/posture/drift | python3 -c 'import sys,json;print(json.load(sys.stdin)["complete"])')"
}
log ""; log "## 5. Reads"
reads

log ""; log "## 6. Ingestor backpressure (REDIS_STREAM_MAX_BACKLOG=10000), worker stopped"
kill $WORKER; wait $WORKER 2>/dev/null
b0=$(blocks); ingest 32 20000 "ingestor accept 20000, worker stopped" | tee -a "$WORK/results.txt"
log "   stream length while the worker is down: $(redis-cli -p 6390 XLEN ernest:events:incoming)"
PORT=3112 METRICS_PORT=9113 "$WORK/ingestor" worker > "$WORK/worker2.log" 2>&1 & PIDS+=($!)
read n secs <<<"$(wait_drained)"; log "   after the worker restarted: $(( n - b0 )) events reached the chain (should equal the 202s above) · stream length after trimming: $(redis-cli -p 6390 XLEN ernest:events:incoming)"

log ""; log "## 5b. Reads, bigger chain"
reads
kill -0 $ERNEST 2>/dev/null && log "Ernest backend still up at the end" || log "Ernest backend DIED"
log "Ernest log size: $(du -h "$WORK/ernest.log" | cut -f1)"
log ""; log "full logs: $WORK"
