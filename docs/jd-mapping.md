# JD requirement → component mapping

What to point at when a job description lists a technology. Kept current as each phase lands.

| JD requirement | Where it lives | Phase | Status |
|---|---|---|---|
| Java 17 (targeting **21**) | `maven.compiler.release=21`; 21 is a superset of 17, and the brief asks for two Java 21 features — see [ADR 0006](adr/0006-target-java-21-not-17.md) | 1, 4 | ✅ |
| — records *(Java 16)* | every event, `ApiError`, `InstrumentView`, `QuoteSnapshot`, commands, all DTOs | 1–4 | ✅ |
| — sealed interfaces *(Java 17)* | `DomainEvent` over 6 event records | 1 | ✅ |
| — pattern matching for `instanceof` *(Java 16)* | entity and composite-key equality | 2 | ✅ |
| — pattern matching for `switch` *(Java **21**, preview in 17)* | `PositionEventListener.apply` — exhaustive over sealed `DomainEvent`, no `default` branch | 4 | ✅ |
| — virtual threads *(Java **21**)* | market-data streaming subscribers, via an injected `AsyncTaskExecutor`; `vthreads` profile on order-service | 2, 4 | ✅ |
| Maven multi-module | parent POM + 8 modules, BOM-managed versions, a Boot auto-configuration library and a shared test-jar | 1, 3, 6 | ✅ |
| Spring Boot 3.x | Boot 3.5.16, all five services | 1 | ✅ |
| — Spring Web | 4 services: orders, book depth, instruments/quotes/SSE, positions/P&L | 2–4 | ✅ |
| — Spring Data JPA + Hibernate | 9 entities, 10 repositories, derived + JPQL + native SQL | 2, 4 | ✅ |
| — Bean Validation | `PlaceOrderRequest`, `@Validated` params, `@ConfigurationProperties` | 2 | ✅ |
| — Spring Security + JWT | RS256 issuer + JWKS at the gateway; all 4 services are resource servers that re-validate ([ADR 0007](adr/0007-asymmetric-jwt-verified-at-every-service.md)) | 5 | ✅ |
| — Actuator | all five services, liveness/readiness probes | 1 | ✅ |
| Spring Cloud Gateway | 8 routes, per-account Redis rate limiting, circuit breaker + fallback, aggregated docs | 5 | ✅ |
| Flyway migrations | 3 schemas, 5 migrations, seeded reference data | 2, 4 | ✅ |
| PostgreSQL, schema per service | `oms_order` (7 tables), `oms_marketdata` (1), `oms_position` (2); 2 append-only triggers, partial indexes, CHECK constraints | 2, 4 | ✅ |
| Apache Kafka | 6 topics, 4 producers, 7 listeners (record + batch), per-consumer retry policies, DLT on all | 1, 2–4 | ✅ |
| Redis | `@Cacheable` reference data in two services + write-through quote snapshots with a safety TTL | 2, 4 | ✅ |
| OpenAPI / Swagger | springdoc on all 5, bearer scheme declared, one Swagger UI aggregating 5 documents | 5 | ✅ |
| Role-based access | `TRADER` / `RISK` / `ADMIN`, enforced at the gateway AND per service; RISK is read-only by design | 5 | ✅ |
| JUnit 5 | every module; Surefire wired | 1 | ✅ |
| Mockito | service-layer tests in 2 services, `@MockitoBean` in 2 slice tests | 2, 4 | ✅ |
| Testcontainers | 5 ITs across 4 services — Postgres, Kafka, Redis; shared `TestJwt` test-jar for auth | 2–4, 6 | ✅ written |
| Coverage > 70% | JaCoCo `coverage-gate` profile, enforced by the CI integration job | 1, 6 | ✅ written |
| Global exception handling | `oms-web`: a Boot auto-configuration shared by all 4 web services, 9 handlers, one contract | 1–4 | ✅ |
| Docker / docker-compose | one parameterised multi-stage Dockerfile, non-root, `docker compose up` brings up the whole stack | 6 | ✅ written |
| Kubernetes manifests | Deployment + Service + PDB per service, ConfigMap, Secret, NetworkPolicies, Ingress, 2 HPAs, kustomize | 6 | ✅ written |
| GitHub Actions CI/CD | 4 jobs: unit, integration + 70% coverage gate, manifest lint, 5-way image matrix to GHCR + Trivy | 6 | ✅ written |
| Structured JSON logging | one shared `logback-spring.xml` in `oms-web`, profile-selected, MDC included wholesale | 6 | ✅ |
| Micrometer + Prometheus + Grafana | real percentile histograms, 7 alerting rules, provisioned 17-panel dashboard | 1, 6 | ✅ written |
| Distributed tracing | Micrometer Tracing → OTLP → Tempo, propagated over Kafka headers so one order is one trace | 6 | ✅ written |

## Domain differentiators — the part that is not on any JD

These are what separate this from a CRUD project, and they are what an interviewer at a
capital-markets employer will actually want to talk about.

| Differentiator | Where | Phase |
|---|---|---|
| Price-time priority matching engine, limit + market, partial fills, IOC/FOK | `matching-engine` | 3 ✅ |
| Documented concurrency design; single-writer-per-book via Kafka partitioning | ADR 0005 + `docs/concurrency.md` | 3 ✅ |
| JMH harness: throughput and p50/p95/p99 latency, plus a GC-profiler allocation figure | `matching-bench` | 3 ✅ |
| A real measure → fix → re-measure tuning pass with before/after numbers | `docs/performance.md` | 3 ✅ |
| Immutable audit trail as source of truth; order row is a derived cache | `order_audit` + append-only trigger | 2 ✅ |
| Transactional outbox — no dual write to Kafka, `FOR UPDATE SKIP LOCKED` | ADR 0004 | 2 ✅ |
| Pre-trade risk suite: 6 ordered checks, worst-case position exposure | `order-service` risk package | 2 ✅ |
| Average-cost realised P&L, mark-to-market unrealised, exact-cost basis | `position-service` | 4 ✅ |
| Backpressure on the market-data stream, with conflation and a published sequence | `market-data-service` | 4 ✅ |
| Idempotent consumers, and the reasoning for at-least-once over EOS | `docs/kafka-event-design.md` §4 | 2–4 ✅ |
| Defence-in-depth auth: the account comes from a signed claim, never a header | ADR 0007 + `docs/security.md` | 5 ✅ |
| Separation of duties in the role model: RISK can read everything and trade nothing | `docs/security.md` §4 | 5 ✅ |
