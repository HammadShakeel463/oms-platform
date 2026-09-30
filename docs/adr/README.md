# Architecture Decision Records

Each ADR records one decision, the alternatives that were rejected, and the cost that was
accepted. An ADR is immutable once accepted — a changed decision is a new ADR that supersedes
the old one.

| # | Decision | Status | Phase |
|---|---|---|---|
| [0001](0001-microservice-boundaries-and-a-shared-contract-module.md) | Microservice boundaries, and a shared contract module that holds no entities | Accepted | 1 |
| [0002](0002-fixed-point-money-long-ticks-in-the-engine-bigdecimal-at-the-boundary.md) | Fixed-point money: `long` ticks in the engine, `BigDecimal` at the boundary | Accepted | 1 |
| [0003](0003-json-events-without-a-schema-registry.md) | JSON event payloads, no Schema Registry, versioned topic names | Accepted | 1 |
| [0004](0004-transactional-outbox-instead-of-dual-write.md) | Transactional outbox instead of a dual write to Kafka | Accepted | 2 |
| [0005](0005-single-writer-per-book-not-a-lock-free-order-book.md) | Single-writer-per-book via Kafka partitioning, not a lock-free order book | Accepted | 3 |
| [0006](0006-target-java-21-not-17.md) | Target Java 21, not Java 17 (the brief asks for two Java 21 features) | Accepted | 4 |
| [0007](0007-asymmetric-jwt-verified-at-every-service.md) | Asymmetric JWT, verified at the gateway and at every service | Accepted | 5 |
