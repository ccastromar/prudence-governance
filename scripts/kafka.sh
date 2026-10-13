#!/usr/bin/env bash
# Local Kafka (KRaft, single node, no ZooKeeper, no Docker) on 127.0.0.1:9092 — the transport
# for Prudence's high-volume decision stream into Ernest (decision-transport=kafka).
#
#   ./scripts/kafka.sh
#   KAFKA_TOPIC_PARTITIONS=16 ./scripts/kafka.sh
#
# Needs `brew install kafka`. Uses Homebrew's server.properties (broker+controller) with the
# listeners bound to loopback; formats the storage on first run and creates the decision
# topic once the broker is up. Data lives in Homebrew's kraft-combined-logs.
set -euo pipefail
KAFKA_HOME="${KAFKA_HOME:-$(brew --prefix kafka 2>/dev/null || true)}"
CONF="${KAFKA_CONFIG:-$(brew --prefix 2>/dev/null)/etc/kafka/server.properties}"
TOPIC="${KAFKA_TOPIC:-prudence.decisions}"
PARTITIONS="${KAFKA_TOPIC_PARTITIONS:-8}"
BOOTSTRAP=127.0.0.1:9092

[ -x "$KAFKA_HOME/bin/kafka-server-start" ] || { echo "Kafka not found — brew install kafka (or set KAFKA_HOME)" >&2; exit 1; }
[ -f "$CONF" ] || { echo "Kafka config not found: $CONF (set KAFKA_CONFIG)" >&2; exit 1; }
for port in 9092 9093; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "port $port is already in use:" >&2; lsof -nP -iTCP:"$port" -sTCP:LISTEN >&2; exit 1
  fi
done

LOG_DIR="$(sed -n 's/^log.dirs=//p' "$CONF" | cut -d, -f1)"
if [ ! -f "$LOG_DIR/meta.properties" ]; then
  echo "formatting KRaft storage in $LOG_DIR"
  "$KAFKA_HOME/bin/kafka-storage" format --standalone -t "$("$KAFKA_HOME/bin/kafka-storage" random-uuid)" -c "$CONF" >/dev/null
fi

# Create the decision topic as soon as the broker answers (idempotent).
(
  for _ in $(seq 1 60); do
    "$KAFKA_HOME/bin/kafka-topics" --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1 && break
    sleep 1
  done
  "$KAFKA_HOME/bin/kafka-topics" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$TOPIC" --partitions "$PARTITIONS" --replication-factor 1 >/dev/null \
    && echo "topic $TOPIC ready ($PARTITIONS partitions)"
) &

echo "Kafka $("$KAFKA_HOME/bin/kafka-topics" --version 2>/dev/null | head -1) on $BOOTSTRAP (KRaft, single node)"
exec "$KAFKA_HOME/bin/kafka-server-start" "$CONF" \
  --override listeners=PLAINTEXT://127.0.0.1:9092,CONTROLLER://127.0.0.1:9093 \
  --override advertised.listeners=PLAINTEXT://127.0.0.1:9092,CONTROLLER://127.0.0.1:9093 \
  --override controller.quorum.bootstrap.servers=127.0.0.1:9093
