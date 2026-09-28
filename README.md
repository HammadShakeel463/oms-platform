# Order Management & Market Data Platform

A simplified but architecturally serious equities trading backend: order intake with a full
audit trail, a price-time priority matching engine, a simulated market data feed, position and
P&L tracking, and pre-trade risk — built as five Spring Boot microservices over Kafka,
PostgreSQL and Redis.

> **Build status:** Phase 2 of 7 complete. order-service is feature-complete end to end.
> `./mvnw test` is green: 73 tests. See [Roadmap](#roadmap).

## Why this project exists

Most Java portfolio projects are CRUD with extra steps. This one is built around the parts of
a trading backend that are actually hard: an append-only order lifecycle you can audit, a
matching engine whose concurrency and allocation behaviour is measured rather than asserted,
and event-driven boundaries where the ordering guarantees are a deliberate design decision
instead of an accident of configuration.

## Stack

| Layer | Technology |
|---|---|
| Language | Java 17 (records, sealed interfaces, pattern matching, virtual threads) |
| Framework | Spring Boot 3.5, Spring Cloud Gateway 2025.0 |
| Persistence | PostgreSQL 16, Hibernate/JPA, Flyway — one schema per service |
| Messaging | Apache Kafka (6 topics, versioned, documented) |
| Cache | Redis 7 |
| Security | Spring Security, JWT, role-based access |
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

## Repository layout

```
oms-platform/
├── pom.xml                    parent: BOM imports, Java 17 release, JaCoCo, Surefire/Failsafe
├── mvnw, mvnw.cmd, .mvn/      Maven wrapper — no local Maven install needed
├── oms-common/                shared CONTRACTS only: domain enums, Ticks, sealed DomainEvent
│                              hierarchy, topic names, error contract. No JPA, no Spring Boot.
├── order-service/       :8081 intake, validation, pre-trade risk, state machine, audit, outbox
├── matching-engine/     :8082 in-memory price-time priority order book
├── market-data-service/ :8083 tick simulator, Redis snapshots, streaming quotes
├── position-service/    :8084 positions, realised (average cost) and unrealised P&L
├── api-gateway/         :8080 Spring Cloud Gateway: routing, JWT, rate limiting
├── docs/                      architecture, domain model, Kafka design, ADRs, diagrams
├── ops/                       topic provisioning, runbook material
├── deploy/k8s/                Kubernetes manifests (Phase 6)
└── .github/workflows/         CI (Phase 6)
```

## Build

Only a JDK 17 or newer is required — Maven bootstraps itself through the wrapper.

```bash
./mvnw verify
```

```bash
./mvnw verify -Pcoverage-gate
```

On Windows use `mvnw.cmd`. The build is currently verified on JDK 25 compiling to release 17;
the `--release 17` flag means the bytecode and the API surface are genuinely Java 17.

Running the full stack (`docker compose up`) arrives in Phase 6.

## Design decisions worth reading first

If you only read three things:

- **Why every order-path topic is keyed by symbol.** It gives the matching engine
  one-writer-per-book without a single lock, and it stops a cancel from overtaking the order it
  cancels. [kafka-event-design.md §2](docs/kafka-event-design.md)
- **Why prices are `long` ticks inside the engine and `BigDecimal` at the edges.** `BigDecimal`
  in an inner matching loop is sustained allocation pressure, and allocation pressure is a p99
  problem, not a throughput problem. [ADR 0002](docs/adr/0002-fixed-point-money-long-ticks-in-the-engine-bigdecimal-at-the-boundary.md)
- **Why at-least-once with idempotent consumers, not Kafka exactly-once.** EOS does not extend
  to the PostgreSQL write, which is where the money lands.
  [kafka-event-design.md §4](docs/kafka-event-design.md)

## Roadmap

| Phase | Contents | Status |
|---|---|---|
| 1 | Architecture, domain model, Kafka event design, Maven skeleton | ✅ Complete |
| 2 | order-service end to end: JPA, Flyway, risk, state machine, outbox, tests | ✅ Complete |
| 3 | matching-engine: order book, concurrency design, JMH harness, tuning pass | ⏳ |
| 4 | market-data-service (backpressure) + position-service (P&L) | ⏳ |
| 5 | api-gateway, Spring Security/JWT, OpenAPI | ⏳ |
| 6 | Docker Compose, Kubernetes, GitHub Actions, observability stack | ⏳ |
| 7 | Architecture diagram, benchmark write-up, interview talking points | ⏳ |
