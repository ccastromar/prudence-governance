#!/usr/bin/env bash
# Ernest (evidence layer) for local Prudence work, from the sibling repo, no Docker: the NestJS
# backend on :3001 with Merkle batching and open access, on a persistent local MongoDB database.
# With --kafka it also runs Ernest's Kafka consumer for decision-transport=kafka (needs
# ./scripts/kafka.sh). Ctrl-C stops everything.
#
#   ./scripts/ernest.sh            # backend only
#   ./scripts/ernest.sh --kafka    # backend + event-ingestor kafka
#   ERNEST_HOME=/path/to/ai-blockchain-provenance ./scripts/ernest.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ERNEST_HOME="${ERNEST_HOME:-$HERE/../../ai-blockchain-provenance}"
MONGODB_URI="${MONGODB_URI:-mongodb://127.0.0.1:27017/ernest_local}"
PORT="${ERNEST_PORT:-3001}"
KAFKA=false; [ "${1:-}" = "--kafka" ] && KAFKA=true

[ -d "$ERNEST_HOME/backend" ] || { echo "Ernest not found at $ERNEST_HOME (set ERNEST_HOME)" >&2; exit 1; }
mongosh --quiet "$MONGODB_URI" --eval 'db.runCommand({ping:1}).ok' >/dev/null 2>&1 || {
  echo "MongoDB not reachable at $MONGODB_URI (brew services start mongodb-community)" >&2; exit 1; }
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "port $PORT is already in use:" >&2; lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2; exit 1
fi

if [ ! -f "$ERNEST_HOME/backend/dist/main.js" ]; then
  echo "building Ernest backend…"
  (cd "$ERNEST_HOME" && pnpm --filter ernest-backend run build)
fi

PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM

# Empty values win over Ernest's own .env: open access, no external anchoring or webhooks.
(cd "$ERNEST_HOME/backend" && EVIDENCE_BATCHING=on ERNEST_API_KEY= ERNEST_READ_API_KEY= ANCHOR_PROVIDER= \
  INFURA_URL= PRIVATE_KEY= CONTRACT_ADDRESS= WEBHOOK_URL= MLFLOW_TRACKING_URI= CORS_ORIGIN='*' \
  MONGODB_URI="$MONGODB_URI" PORT="$PORT" exec node dist/main.js) &
PIDS+=($!)
echo "Ernest on http://127.0.0.1:$PORT (db ${MONGODB_URI##*/})"

if $KAFKA; then
  for _ in $(seq 1 30); do curl -sf -o /dev/null "http://127.0.0.1:$PORT/health" && break; sleep 1; done
  (cd "$ERNEST_HOME/event-ingestor" && go build -o "${TMPDIR:-/tmp}/ernest-event-ingestor" ./cmd/event-ingestor)
  MONGODB_URI="$MONGODB_URI" MONGO_DB_NAME="${MONGODB_URI##*/}" EVIDENCE_BATCHING=on \
    KAFKA_BROKERS="${KAFKA_BROKERS:-127.0.0.1:9092}" KAFKA_TOPICS="${KAFKA_TOPICS:-prudence.decisions}" \
    KAFKA_GROUP="${KAFKA_GROUP:-ernest-ingestor}" METRICS_PORT="${METRICS_PORT:-9102}" \
    "${TMPDIR:-/tmp}/ernest-event-ingestor" kafka &
  PIDS+=($!)
  echo "Ernest Kafka consumer on ${KAFKA_TOPICS:-prudence.decisions} (${KAFKA_BROKERS:-127.0.0.1:9092})"
fi
wait
