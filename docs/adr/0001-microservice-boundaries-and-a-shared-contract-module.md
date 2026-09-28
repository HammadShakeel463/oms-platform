# ADR 0001 — Microservice boundaries, and a shared contract module that holds no entities

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

Five Spring Boot services need to exchange orders, fills, ticks and reference data. Two
things have to be decided together, because getting one right and the other wrong produces a
distributed monolith: where the service boundaries fall, and what code they are allowed to
share.

The pull towards sharing is strong. All five services need `OrderStatus`, all four business
services need the event records, and every service needs the same error shape. Duplicating
those is genuinely worse — five copies of a state machine drift, and then an audit trail
disagrees with itself. But the usual next step, a shared `oms-domain` module containing the
JPA entities, is how teams end up unable to deploy one service without the other four.

## Decision

**Boundaries** are drawn along three axes rather than one entity per service:

1. *Consistency* — the order lifecycle needs one transaction across the order row and its
   audit row, so it is one service with one schema (`order-service`).
2. *Performance* — the matching engine needs an in-memory hot path free of JPA, HTTP thread
   pools and somebody else's GC behaviour, so it is its own process with no database.
3. *Fan-out profile* — market data is high-volume and conflatable; positions are low-volume
   and strictly ordered. Different scaling curves, different services.

**Sharing** is allowed for contracts and forbidden for persistence:

- `oms-common` contains: domain enums (`Side`, `OrderType`, `TimeInForce`, `OrderStatus` and
  its transition table), fixed-point money (`Ticks`), the sealed `DomainEvent` hierarchy and
  its records, topic names, reference-data DTOs, and the error contract.
- `oms-common` declares exactly three dependencies: `jackson-databind`,
  `jackson-datatype-jsr310`, `jakarta.validation-api`. **No** `spring-boot-starter-data-jpa`,
  **no** Spring Boot at all.
- Each service owns a PostgreSQL schema (`oms_order`, `oms_marketdata`, `oms_position`) with
  its own Flyway migration path and its own credentials. No cross-schema queries, no foreign
  keys across schemas.

The no-JPA rule is structural, not aspirational: without the dependency on the classpath,
`@Entity` does not compile. An ArchUnit test in Phase 2 additionally asserts that no service
package imports another service's package.

## Alternatives considered

**No shared module; duplicate the types per service.** Maximum decoupling, and it is what a
strict reading of the microservice literature prescribes. Rejected because the duplication
here is of *invariants*, not of convenience code. Five independently maintained copies of the
order state machine is five chances for the audit trail to disagree about what a legal history
is, and that is the one thing this system must never get wrong. The coupling a shared contract
creates is real but bounded and versioned; the coupling five drifting copies create is
unbounded and invisible.

**Share entities in an `oms-domain` module.** Rejected. It makes every schema change a
coordinated deploy of everything that depends on the module, which is precisely the property
microservices exist to remove. It also quietly permits a service to query another service's
tables, at which point the service boundary is decoration.

**One service, modular monolith.** Genuinely the right answer for a system of this size, and
worth saying so in an interview rather than pretending otherwise. Rejected here because the
project's stated goal includes demonstrating a distributed architecture, and because the
matching engine's isolation requirement is real: a modular monolith would give its hot path
the same JVM, the same heap and the same GC as the JPA layer.

## Consequences

**Good**

- A schema change inside one service is invisible to the others.
- The contract module is small, dependency-light and fast to build, so it is cheap to keep
  the version aligned across services.
- Services can be written in phases and tested independently, which is what makes this
  project buildable in the order it is built.

**Costs, accepted**

- `oms-common` is a shared release artifact. A breaking change to it is a coordinated
  release. Mitigated by the schema-evolution rules in `docs/kafka-event-design.md` §7:
  additive-only within a major version.
- Reference data is duplicated: market-data-service owns instruments, order-service caches
  them in Redis. The cache can be stale. Accepted deliberately — an instrument's lot size and
  tick size change roughly never, and a validation path that hard-fails when
  market-data-service is down is a worse trade than one that validates against a
  five-minute-old lot size.
- Queries that span services (an order joined to its resulting position) cannot be one SQL
  statement. They are composed at the gateway or by the client.

## Notes for the C++ reader

`oms-common` plays the role of a header-only interface library — the shared types every
translation unit agrees on — and the services are separate binaries linking it. The
difference worth internalising is *when* the coupling bites. In C++, an ABI break in a shared
header is caught at link time. Here, a mismatched `oms-common` version between producer and
consumer is caught at *runtime*, on a deserialisation error, in production. That is why the
schema-evolution rules and `EventContractTest` exist: they are the missing link-time check,
reconstructed by hand.
