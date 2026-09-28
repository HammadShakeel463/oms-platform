# JD requirement → component mapping

What to point at when a job description lists a technology. Kept current as each phase lands.

| JD requirement | Where it lives | Phase | Status |
|---|---|---|---|
| Java 17 | `maven.compiler.release=17`; records, sealed interfaces, pattern matching, `EnumMap`/`EnumSet` | 1 | ✅ |
| — records | every event, `ApiError`, `InstrumentView`, `QuoteSnapshot` | 1 | ✅ |
| — sealed interfaces | `DomainEvent` | 1 | ✅ |
| — pattern matching for switch | event dispatch in order-service / position-service | 2, 4 | ⏳ |
| — virtual threads | market-data streaming endpoint; outbox publisher | 4, 2 | ⏳ |
| Maven multi-module | parent POM + 6 modules, BOM-managed versions | 1 | ✅ |
| Spring Boot 3.x | Boot 3.5.16, all five services | 1 | ✅ |
| — Spring Web | order / matching / market-data / position controllers | 2–4 | ⏳ |
| — Spring Data JPA + Hibernate | order, market-data, position repositories | 2, 4 | ⏳ |
| — Bean Validation | `@Valid` request records in every controller | 2 | ⏳ |
| — Spring Security + JWT | gateway filter + per-service resource-server config | 5 | ⏳ |
| — Actuator | all five services, liveness/readiness probes | 1 | ✅ |
| Spring Cloud Gateway | `api-gateway` | 5 | ⏳ (skeleton up) |
| Flyway migrations | one migration path per schema | 2, 4 | ⏳ |
| PostgreSQL, schema per service | `oms_order`, `oms_marketdata`, `oms_position` | 2, 4 | ⏳ |
| Apache Kafka | 6 topics, documented design | 1 (design) 2–4 (code) | ✅ design |
| Redis | instrument reference cache, quote snapshot cache | 2, 4 | ⏳ |
| OpenAPI / Swagger | springdoc on each service, aggregated at the gateway | 5 | ⏳ |
| Role-based access | `ROLE_TRADER` / `ROLE_RISK` / `ROLE_ADMIN` | 5 | ⏳ |
| JUnit 5 | every module; Surefire wired | 1 | ✅ |
| Mockito | service-layer unit tests | 2 | ⏳ |
| Testcontainers | PostgreSQL + Kafka + Redis integration tests, Failsafe wired for `*IT` | 2–4 | ⏳ |
| Coverage > 70% | JaCoCo `coverage-gate` profile, enforced in CI | 1 (wired) 6 (gated) | ✅ wired |
| Global exception handling | `ApiError` + `@RestControllerAdvice` | 1 (contract) 2 (advice) | ✅ contract |
| Docker / docker-compose | one-command full stack | 6 | ⏳ |
| Kubernetes manifests | Deployment, Service, ConfigMap, Secret per service | 6 | ⏳ |
| GitHub Actions CI/CD | build, test, coverage gate, image publish | 6 | ⏳ |
| Structured JSON logging | logstash-logback-encoder, MDC with trace + order id | 6 | ⏳ |
| Micrometer + Prometheus + Grafana | registry wired in every service; dashboard JSON | 1 (wired) 6 (dashboard) | ✅ wired |
| Distributed tracing | Micrometer Tracing → OTLP, propagated over Kafka headers | 6 | ⏳ |

## Domain differentiators — the part that is not on any JD

These are what separate this from a CRUD project, and they are what an interviewer at a
capital-markets employer will actually want to talk about.

| Differentiator | Where | Phase |
|---|---|---|
| Price-time priority matching engine, limit + market, partial fills, IOC/FOK | `matching-engine` | 3 |
| Documented concurrency design; single-writer-per-book via Kafka partitioning | ADR 0005 + `docs/concurrency.md` | 3 |
| JMH harness: throughput and p50/p95/p99 matching latency | `matching-engine` bench module | 3 |
| A real measure → fix → re-measure tuning pass with before/after numbers | `docs/performance.md` | 3 |
| Immutable audit trail as source of truth; order row is a derived cache | `order_audit` | 2 |
| Transactional outbox — no dual-write to Kafka | ADR 0004 | 2 |
| Pre-trade risk suite: notional, position, fat-finger band, lot/tick size | `order-service` risk package | 2 |
| Average-cost realised P&L, mark-to-market unrealised | `position-service` | 4 |
| Backpressure on the market-data stream, with conflation | `market-data-service` | 4 |
| Idempotent consumers, and the reasoning for at-least-once over EOS | `docs/kafka-event-design.md` §4 | 2–4 |
