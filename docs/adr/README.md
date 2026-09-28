# Architecture Decision Records

Each ADR records one decision, the alternatives that were rejected, and the cost that was
accepted. An ADR is immutable once accepted — a changed decision is a new ADR that supersedes
the old one.

| # | Decision | Status | Phase |
|---|---|---|---|
| [0001](0001-microservice-boundaries-and-a-shared-contract-module.md) | Microservice boundaries, and a shared contract module that holds no entities | Accepted | 1 |
| [0002](0002-fixed-point-money-long-ticks-in-the-engine-bigdecimal-at-the-boundary.md) | Fixed-point money: `long` ticks in the engine, `BigDecimal` at the boundary | Accepted | 1 |
| [0003](0003-json-events-without-a-schema-registry.md) | JSON event payloads, no Schema Registry, versioned topic names | Accepted | 1 |
| 0004 | Transactional outbox instead of dual-write to Kafka | Planned | 2 |
| 0005 | Single-writer-per-book concurrency instead of a lock-free order book | Planned | 3 |
