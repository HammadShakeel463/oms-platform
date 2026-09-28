# Topic provisioning

Topics are created explicitly, never by broker auto-creation. Auto-created topics get the
broker default partition count and replication factor, which is how a production topic ends
up with one partition and no redundancy.

Locally, the `kafka-init` service in `docker-compose.yml` runs this script once the broker is
healthy (Phase 6). In a cluster, the same list is applied by a Kubernetes Job.

```bash
#!/usr/bin/env bash
# ops/create-topics.sh  — idempotent: --if-not-exists
set -euo pipefail
BROKER="${KAFKA_BROKER:-kafka:9092}"
RF="${REPLICATION_FACTOR:-1}"        # 1 locally, 3 in a cluster

create() {
  local topic=$1 partitions=$2 retention_ms=$3
  kafka-topics.sh --bootstrap-server "$BROKER" --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor "$RF" \
    --config retention.ms="$retention_ms" \
    --config min.insync.replicas=$(( RF > 1 ? 2 : 1 ))
}

#      topic                                parts  retention
create oms.orders.accepted.v1                 12   604800000    # 7d
create oms.orders.cancel-requests.v1          12   604800000    # 7d
create oms.orders.execution-reports.v1        12   604800000    # 7d
create oms.trades.executed.v1                 12  2592000000    # 30d
create oms.orders.lifecycle.v1                12  2592000000    # 30d
create oms.marketdata.ticks.v1                12     3600000    # 1h

# Dead-letter topics: fewer partitions, long retention, nothing in them normally.
for t in oms.orders.accepted.v1 oms.orders.cancel-requests.v1 \
         oms.orders.execution-reports.v1 oms.trades.executed.v1 \
         oms.orders.lifecycle.v1 oms.marketdata.ticks.v1; do
  create "${t}.dlt" 3 2592000000
done
```

## Settings that matter and why

| Setting | Value | Reason |
|---|---|---|
| `min.insync.replicas` | 2 (RF=3) | With `acks=all`, a write is acknowledged only once 2 replicas hold it. `acks=all` with `min.insync.replicas=1` is a durability illusion. |
| `retention.ms` on ticks | 1 h | Tick history is not replayed from Kafka; keeping 30 days of it would dominate disk for no benefit. |
| `retention.ms` on trades | 30 d | Long enough to rebuild positions from scratch, which is the disaster-recovery story. |
| `cleanup.policy` | `delete` everywhere | No topic is currently used as a KTable. If `orders.lifecycle` becomes one, it flips to `compact` — which is why it is keyed by `orderId`. |
| auto-create | **off** (`auto.create.topics.enable=false`) | A typo in a topic name should fail loudly, not silently create a topic nobody monitors. |

## Operational commands

```bash
# Consumer group lag — the first thing to look at when anything feels slow
kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group position-service-trades

# What actually landed in a DLT
kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic oms.trades.executed.v1.dlt --from-beginning --property print.headers=true

# Replay a DLT back to the source topic after fixing the bug
kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic oms.trades.executed.v1.dlt \
    --from-beginning --max-messages 100 \
  | kafka-console-producer.sh --bootstrap-server kafka:9092 --topic oms.trades.executed.v1
```
