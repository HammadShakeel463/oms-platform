#!/usr/bin/env bash
#
# Creates every platform topic explicitly, with its partition count and retention.
#
# Topics are never left to broker auto-creation. An auto-created topic gets the broker
# default partition count and replication factor, which is how a production topic ends up
# with one partition and no redundancy - discovered during the incident it causes.
#
# Idempotent: --if-not-exists means re-running this is a no-op, so it is safe as a
# docker-compose init container or a Kubernetes Job that may be retried.
set -euo pipefail

BROKER="${KAFKA_BROKER:-kafka:9092}"
RF="${REPLICATION_FACTOR:-1}"          # 1 locally, 3 in a cluster
MIN_ISR=$(( RF > 1 ? 2 : 1 ))

# The image ships the scripts without the .sh suffix in some builds; tolerate both.
if command -v kafka-topics.sh >/dev/null 2>&1; then
  TOPICS=kafka-topics.sh
else
  TOPICS=/opt/kafka/bin/kafka-topics.sh
fi

echo "Waiting for ${BROKER} ..."
for _ in $(seq 1 60); do
  if "$TOPICS" --bootstrap-server "$BROKER" --list >/dev/null 2>&1; then
    echo "Broker is up."
    break
  fi
  sleep 2
done

create() {
  local topic=$1 partitions=$2 retention_ms=$3
  echo "  ${topic}  (partitions=${partitions}, retention=${retention_ms}ms)"
  "$TOPICS" --bootstrap-server "$BROKER" --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor "$RF" \
    --config "retention.ms=${retention_ms}" \
    --config "min.insync.replicas=${MIN_ISR}" >/dev/null
}

echo "Creating platform topics ..."
#      topic                                parts  retention
create oms.orders.accepted.v1                 12   604800000    # 7d
create oms.orders.cancel-requests.v1          12   604800000    # 7d
create oms.orders.execution-reports.v1        12   604800000    # 7d
create oms.trades.executed.v1                 12  2592000000    # 30d - positions rebuild from this
create oms.orders.lifecycle.v1                12  2592000000    # 30d - audit stream
create oms.marketdata.ticks.v1                12     3600000    # 1h  - nobody replays yesterday

# Dead-letter topics: fewer partitions, long retention, empty in normal operation.
echo "Creating dead-letter topics ..."
for t in oms.orders.accepted.v1 oms.orders.cancel-requests.v1 \
         oms.orders.execution-reports.v1 oms.trades.executed.v1 \
         oms.orders.lifecycle.v1 oms.marketdata.ticks.v1; do
  create "${t}.dlt" 3 2592000000
done

echo
echo "Topics now present:"
"$TOPICS" --bootstrap-server "$BROKER" --list | sort
