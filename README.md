# Order Management & Market Data Platform

A simplified but architecturally serious equities trading backend: order intake with a full
audit trail, a price-time priority matching engine, a simulated market data feed, position and
P&L tracking, and pre-trade risk — built as five Spring Boot microservices over Kafka,
PostgreSQL and Redis.

> **Build status:** Phase 6 of 7 complete. `./mvnw test` is green: 330 tests.
>
> The container, cluster and CI paths are written and statically checked but **have not been
> executed** - there is no Docker daemon on the machine this was authored on. See
> [deployment.md section 7](docs/deployment.md#7-what-is-not-yet-verified) for exactly what to
> run first.

## Why this project exists

Most Java portfolio projects are CRUD with extra steps. This one is built around the parts of
a trading backend that are actually hard: an append-only order lifecycle you can audit, a
matching engine whose concurrency and allocation behaviour is measured rather than asserted,
and event-driven boundaries where the ordering guarantees are a deliberate design decision
instead of an accident of configuration.

## Stack

| Layer | Technology |
|---|---|
| Language | Java 21 LTS — records, sealed interfaces, exhaustive switch patterns, virtual threads ([ADR 0006](docs/adr/0006-target-java-21-not-17.md)) |
| Framework | Spring Boot 3.5, Spring Cloud Gateway 2025.0 |
| Persistence | PostgreSQL 16, Hibernate/JPA, Flyway — one schema per service |
| Messaging | Apache Kafka (6 topics, versioned, documented) |
| Cache | Redis 7 |
| Security | Spring Security, RS256 JWT with JWKS, role-based access, per-account rate limiting |
| API docs | OpenAPI 3 / springdoc |
| Build | Maven multi-module (wrapper committed — a JDK is the only prerequisite) |
| Test | JUnit 5, Mockito, AssertJ, Testcontainers, JMH, ArchUnit |
| Ops | Docker Compose, Kubernetes manifests, GitHub Actions |
| Observability | Micrometer → Prometheus → Grafana, OTLP tracing, JSON logs |

## Documentation

Read in this order:

1. **[docs/architecture.md](docs/architecture.md)** — the shape of the system, service
   ownership, sync vs async rules, the end-to-end order flow, and what is deliberately absent.
2. **[docs/domain-model.md](docs/domain-model.md)** — ubiquitous language, the order lifecycle
   and audit trail, why prices are fixed-point, why the event hierarchy is sealed, the error
   contract, and the risk checks.
3. **[docs/kafka-event-design.md](docs/kafka-event-design.md)** — topics, partition keys and
   *why each key is that key*, the event catalogue, delivery semantics, retry/DLT policy,
   consumer group layout and schema evolution rules.
4. **[docs/adr/](docs/adr/)** — the decisions, with the rejected alternatives and the accepted
   costs.
5. **[docs/jd-mapping.md](docs/jd-mapping.md)** — which component satisfies which job-spec
   requirement.
6. **[ops/kafka-topics.md](ops/kafka-topics.md)** — topic provisioning and the operational
   commands that matter.
7. **[docs/order-service.md](docs/order-service.md)** — the first service in full: API, error
   contract, schema, order flow, risk suite, configuration and the test pyramid.
8. **[docs/matching-engine.md](docs/matching-engine.md)** — the order book: data structure, matching
   algorithm, allocation discipline, recovery.
9. **[docs/concurrency.md](docs/concurrency.md)** — the threading design, in Java Memory Model
   terms, with the C++ mapping.
10. **[docs/performance.md](docs/performance.md)** — the measure → fix → re-measure pass, with
    before/after numbers and the reproduction command.
11. **[docs/market-data-service.md](docs/market-data-service.md)** — the tick simulator, and the
    backpressure design: why conflation is the only correct option for a quote stream.
12. **[docs/position-service.md](docs/position-service.md)** — average-cost P&L, why the state is
    exact cost rather than a rounded average, and the case implementations get wrong.
13. **[docs/security.md](docs/security.md)** — the authorisation matrix, why every service validates
    the token independently, and what is deliberately not covered.
14. **[docs/deployment.md](docs/deployment.md)** — images, the one-command stack, Kubernetes, CI,
    and an explicit list of what has not been verified.
15. **[docs/observability.md](docs/observability.md)** — the three signals, why the matching timer
    is a histogram and not a mean, and how a trace crosses Kafka.

## Repository layout

```
oms-platform/
├── pom.xml                    parent: BOM imports, Java 17 release, JaCoCo, Surefire/Failsafe
├── mvnw, mvnw.cmd, .mvn/      Maven wrapper — no local Maven install needed
├── oms-common/                shared CONTRACTS only: domain enums, Ticks, sealed DomainEvent
│                              hierarchy, topic names, error contract. No JPA, no Spring Boot.
├── oms-web/                   shared Spring web plumbing (ApiError advice, trace-id filter),
│                              shipped as a Boot auto-configuration
├── matching-bench/            JMH benchmarks for the order book. Not deployed.
├── order-service/       :8081 intake, validation, pre-trade risk, state machine, audit, outbox
├── matching-engine/     :8082 in-memory price-time priority order book
├── market-data-service/ :8083 tick simulator, Redis snapshots, streaming quotes
├── position-service/    :8084 positions, realised (average cost) and unrealised P&L
├── api-gateway/         :8080 Spring Cloud Gateway: routing, JWT, rate limiting
├── docs/                      architecture, domain model, Kafka design, security, deployment,
│                              observability, performance, 7 ADRs
├── ops/                       topic + Postgres bootstrap, Prometheus rules, Grafana dashboard
├── deploy/k8s/base/           Deployment/Service/PDB per service, ConfigMap, Secret,
│                              NetworkPolicies, Ingress, HPAs, kustomization
├── Dockerfile                 one parameterised multi-stage build for all five services
├── docker-compose.yml         the whole platform, one command
└── .github/workflows/ci.yml   unit, integration + coverage gate, manifest lint, image publish
```

## Build

Only a JDK 21 or newer is required — Maven bootstraps itself through the wrapper.

```bash
./mvnw verify
```

```bash
./mvnw verify -Pcoverage-gate
```

On Windows use `mvnw.cmd`. The build is verified on JDK 25 compiling to `--release 21`. The brief asked for Java 17; 21 is a
strict superset and is required by two features the brief also asks for - virtual threads and
pattern matching for switch. See [ADR 0006](docs/adr/0006-target-java-21-not-17.md).

```bash
docker compose up --build
```

Brings up PostgreSQL, Kafka, Redis, all five services, Prometheus, Tempo and Grafana. The API is
on :8080, Swagger UI at /swagger-ui.html, Grafana on :3000. See
[docs/deployment.md](docs/deployment.md) - and note that this path is not yet verified.

## Design decisions worth reading first

If you only read seven things:

- **Why every order-path topic is keyed by symbol.** It gives the matching engine
  one-writer-per-book without a single lock, and it stops a cancel from overtaking the order it
  cancels. [kafka-event-design.md §2](docs/kafka-event-design.md)
- **Why prices are `long` ticks inside the engine and `BigDecimal` at the edges.** `BigDecimal`
  in an inner matching loop is sustained allocation pressure, and allocation pressure is a p99
  problem, not a throughput problem. [ADR 0002](docs/adr/0002-fixed-point-money-long-ticks-in-the-engine-bigdecimal-at-the-boundary.md)
- **Why at-least-once with idempotent consumers, not Kafka exactly-once.** EOS does not extend
  to the PostgreSQL write, which is where the money lands.
  [kafka-event-design.md §4](docs/kafka-event-design.md)
- **Why the matching engine is single-writer instead of lock-free.** Concurrent mutation of one
  book would destroy the price-time ordering the book exists to establish - the concurrency win
  is a partitioning decision, not a clever lock.
  [ADR 0005](docs/adr/0005-single-writer-per-book-not-a-lock-free-order-book.md)
- **Why the quote stream conflates instead of queueing.** An unbounded queue kills the process for
  everyone because of one slow client; conflation bounds memory by the symbol universe and gives a
  lagging subscriber the *freshest* price rather than a faithful replay of stale ones - and the
  published sequence number means the client can see it happened.
  [market-data-service.md](docs/market-data-service.md)
- **Why a position stores exact open cost, not an average cost.** Recomputing an average from a
  rounded average compounds error; storing exact cost makes a full close balance to the last paisa,
  and a CHECK constraint fails the write if it ever does not.
  [position-service.md](docs/position-service.md)
- **Why every service re-validates the JWT instead of trusting a gateway header.** Trusting the
  header means one network misconfiguration - a debug port, a stray `port-forward` - is a total
  authorisation bypass. The security boundary has to be the service, not the topology.
  [ADR 0007](docs/adr/0007-asymmetric-jwt-verified-at-every-service.md)

## Roadmap

| Phase | Contents | Status |
|---|---|---|
| 1 | Architecture, domain model, Kafka event design, Maven skeleton | ✅ Complete |
| 2 | order-service end to end: JPA, Flyway, risk, state machine, outbox, tests | ✅ Complete |
| 3 | matching-engine: order book, concurrency design, JMH harness, tuning pass | ✅ Complete |
| 4 | market-data-service (backpressure) + position-service (P&L) | ✅ Complete |
| 5 | api-gateway, Spring Security/JWT, OpenAPI | ✅ Complete |
| 6 | Docker Compose, Kubernetes, GitHub Actions, observability stack | ✅ Complete |
| 7 | Architecture diagram, benchmark write-up, interview talking points | ⏳ |
