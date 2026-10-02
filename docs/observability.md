# Observability

Three signals, and what each one is for: **metrics** say something is wrong, **traces** say
where, **logs** say why.

> Written and statically checked; the Grafana dashboard and the trace pipeline have not been
> executed — see [deployment.md §7](deployment.md#7-what-is-not-yet-verified).

---

## 1. Structured logging

One `logback-spring.xml`, in `oms-web`, used by all five services. Five identical copies would be
five places for a field name to drift, and a log aggregator cannot query across services whose
fields are named differently.

Format is selected by **Spring profile**, not by a Logback `<if>`:

| Profile | Output |
|---|---|
| default (local) | `17:28:04.113 INFO [4bf92f35…] c.o.o.service.OrderService - Accepted order …` |
| `docker`, `k8s` or `json` | one JSON object per line |

**Why `<springProfile>` and not `<if>`.** Logback's `<if>` requires the Janino library at runtime.
Without it Logback *skips the conditional block entirely* — leaving the root logger with no
appender and the service silently producing no logs at all. That is a genuinely nasty failure:
everything appears to work until you need a log line. `<springProfile>` is handled by Boot and
needs nothing extra.

```json
{"@timestamp":"2026-10-02T17:28:04.113Z","level":"INFO","logger_name":"c.o.o.service.OrderService",
 "thread_name":"http-nio-8081-exec-3","message":"Accepted order 1f7c… BUY 1000 HBL for ACC-TRADER-1",
 "service":"order-service","env":"docker",
 "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7","orderId":"1f7c…"}
```

JSON is not prettier, it is **queryable**. "Every WARN for `traceId` X across all five services"
is a field lookup against structured logs and a regex guess against text ones.

**The MDC is included wholesale**, which is what carries `traceId`, `spanId`, `orderId` and
`tradeId` — placed there by the trace filter and the Kafka listeners — without any log statement
passing them explicitly.

**Stack traces are shortened** to 30 frames with Spring AOP and filter frames excluded. A full
Spring stack trace is well over a hundred frames of framework internals, and the ones that matter
are at the top and the bottom. Keeping everything makes logs expensive to store and harder to
read, which is how people end up not reading them.

---

## 2. Metrics

Micrometer → Prometheus, at `/actuator/prometheus` on every service. **That endpoint requires the
`ADMIN` role** — it carries order rates, book depth and per-account activity, and an
unauthenticated metrics endpoint is a slow information leak nobody notices.

### The ones that are not boilerplate

| Metric | Type | What it is for |
|---|---|---|
| `oms_engine_match` | timer, **percentile histogram** | p50/p95/p99/p99.9 of matching one order |
| `oms_outbox_backlog` | gauge | unpublished outbox rows — normally 0 |
| `oms_risk_rejections_total` | counter, tagged `check` | which pre-trade limit fired |
| `oms_order_transitions_total` | counter, tagged `from`/`to` | the lifecycle state machine as a flow |
| `oms_fills_duplicate_total` | counter | replays suppressed by idempotency |
| `oms_fills_orphaned_total` | counter | a fill for an order order-service never saw |
| `oms_marketdata_conflated_total` | gauge | ticks that superseded an undelivered tick |
| `oms_engine_book_spread_ticks` | gauge, **NaN** when one-sided | spread per symbol |
| `oms_auth_login_total` | counter, tagged `outcome` | credential stuffing, visibly |

**`oms_engine_match` publishes a real histogram, not a mean.** A latency mean is close to useless
here: in the JMH benchmark the baseline's mean was **55× its median**, because 75% of operations
were cheap and 25% cost tens of microseconds. The average described neither population, and a p99
cannot be recovered from it afterwards.

**`oms_engine_book_spread_ticks` reports `NaN` for a one-sided book, not `0`.** Prometheus treats
NaN as absent, so a one-sided market leaves a *gap* in the graph instead of a plausible wrong
number that somebody later writes an alert against.

**Every engine gauge reads the published immutable snapshot, never the live book.** A metrics
scrape is just another reader (ADR 0005). The naive implementation — walking the book's TreeMap
from the scrape thread — would be a data race on the hot path, sampled every fifteen seconds, in
production, where it would be nearly impossible to attribute.

### Alerting rules

`ops/observability/rules.yml`. Deliberately short: a rule that fires on a metric nobody acts on
trains people to ignore alerts.

| Alert | Severity | Why it is a page |
|---|---|---|
| `DeadLetterTopicNotEmpty` | critical | A message the platform could not process after every retry |
| `OrphanedFills` | critical | The engine and the order store disagree about reality |
| `OutboxBacklogGrowing` | critical | **Orders are being accepted and never worked — while the API still returns 201** |
| `NoTicksPublished` | warning | The feed stopping is silent: cached quotes answer until the TTL expires, then market orders start being rejected for want of a reference price |
| `MatchingLatencyP99High` | warning | Benchmarked p99 is ~500ns; 1ms means GC, a pathological book or CPU starvation |
| `RiskRejectionSpike` | warning | Usually a stale reference price or a client looping |

`OutboxBacklogGrowing` is the most valuable one in the file. Kafka being unreachable does not make
order entry fail — the outbox is durable, so orders keep being accepted and keep returning 201 to
clients, and nothing is worked. Without this alert the first symptom is a customer asking why
their order never filled.

---

## 3. Tracing

Micrometer Tracing → OpenTelemetry → OTLP → Tempo.

**One order is one trace, across five services and two topics.** The gateway is the root span;
order-service's validation, reference-data lookup and transaction are children; and the trace
continues *through Kafka* into matching-engine and position-service.

That last part is the bit worth knowing about: it works because

```yaml
spring.kafka.template.observation-enabled: true
spring.kafka.listener.observation-enabled: true
```

makes Spring Kafka inject the W3C `traceparent` into record headers on send and resume the context
on receive. Without those two flags you get **five unrelated traces** and no way to connect an
order to the fill it produced — which is exactly the question you want to ask when a fill looks
wrong.

Sampling is `1.0` locally and `0.1` in the cluster. At real volume, sampling everything makes the
exporter and the trace backend the bottleneck; the production refinement is tail-based sampling in
a collector, so slow and errored traces are kept and the boring ones are dropped.

The `traceId` is in the MDC, so **the trace id in a log line, the one in an `ApiError` response
body, and the one on the span are the same string.** A support ticket quoting a trace id becomes a
trace lookup in one step. That is why `ApiError` has carried a `traceId` field since Phase 1.

---

## 4. Dashboard

`ops/observability/grafana/dashboards/oms-platform.json`, provisioned from the repository rather
than clicked together in the UI — a dashboard that exists only in Grafana's database disappears
with the volume and cannot be code-reviewed.

Laid out so the **top row answers "is the platform healthy"** and the rows below answer "why not":

| Row | Panels |
|---|---|
| Health | services up, outbox backlog, dead-lettered (5m), orders/s, ticks/s |
| Matching engine | latency percentiles, trades/cancels/duplicates, book depth, spread |
| Order path | lifecycle transitions (stacked), risk rejections by check, placement latency, fills vs duplicates |
| Market data and gateway | subscribers and conflation, quote cache hit ratio, auth outcomes |
| JVM (collapsed) | heap, GC pause, allocation rate |

The JVM row is collapsed because it is where you look *third*, not first — but it is there because
allocation rate and GC pause are the mechanism behind the matching engine's whole design
(docs/performance.md), and a p99 regression with a flat allocation rate is a different problem from
one with a rising it.

---

## 5. Health probes

Three endpoints, three jobs:

| Endpoint | Used by | Includes |
|---|---|---|
| `/actuator/health/liveness` | Kubernetes liveness + startup | process is alive |
| `/actuator/health/readiness` | Kubernetes readiness, compose healthcheck | `db`, `ping` |
| `/actuator/health` | humans | everything |

**Readiness deliberately excludes Redis.** A cold cache degrades latency; it does not break
correctness. Removing a pod from rotation because a cache is unavailable would be an outage
*caused by* the cache.

Liveness and readiness are **separate concerns and not interchangeable**: liveness failing
restarts the pod, readiness failing only removes it from the Service endpoints. Pointing liveness
at a check that includes the database means a brief database blip restarts every pod in the
platform simultaneously — turning a recoverable dependency failure into a full outage.

---

## 6. Summary for an interview

1. **Logs are JSON with the MDC included**, so `traceId` correlates a log line, an error response
   and a span without any call site passing it. One config in a shared module, because a field name
   that differs per service cannot be queried across services.
2. **The matching timer publishes a histogram, not a mean** — the baseline's mean was 55× its
   median, so an average would have described no operation that actually happened.
3. **Traces cross Kafka**, via `observation-enabled` on the template and the listener. Without it,
   one order is five unrelated traces and you cannot connect an order to its fill.
4. **The most valuable alert is the outbox backlog**, because Kafka being down does not make order
   entry fail — orders keep returning 201 and nothing works them. That is the failure that is
   invisible without a metric.
5. **Metrics gauges read the published snapshot, never the live book.** A scrape is another reader,
   and walking a mutable TreeMap from the scrape thread would be a data race on the hot path
   sampled every fifteen seconds in production.
