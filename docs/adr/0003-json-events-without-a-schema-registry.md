# ADR 0003 — JSON event payloads, no Schema Registry, versioned topic names

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

Six event types cross Kafka between five services. Something has to guarantee that a
producer's change does not silently break a consumer at 09:15 on a trading day. The realistic
options are Avro or Protobuf with a Confluent Schema Registry, or JSON with a
convention-and-test discipline.

Registry-backed Avro is the default recommendation in the Kafka world, and for good reasons:
compact binary payloads, machine-checked compatibility rules enforced *at publish time*, and
generated types that cannot drift from the schema.

## Decision

Use **JSON** (Jackson, records) with:

1. **Major version in the topic name** — `oms.orders.accepted.v1`. A breaking change is a new
   topic, not a mutation of an existing one.
2. **`schemaVersion` in every payload**, so a message read from a DLT or an archive is
   self-describing.
3. **One concrete type per topic.** No `@JsonTypeInfo`, no polymorphic deserialisation — a
   consumer deserialises to exactly the class that topic carries. Polymorphic JSON
   deserialisation is also a well-known deserialisation-gadget attack surface, and avoiding it
   entirely is cheaper than configuring it safely.
4. **Written evolution rules** (`docs/kafka-event-design.md` §7): additive optional fields
   only; never rename, retype or remove within a major version.
5. **Consumers configured lenient**: `FAIL_ON_UNKNOWN_PROPERTIES=false` so an old consumer
   tolerates a new field, and `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE=true` so a new
   enum constant does not throw in an old consumer.
6. **A contract test in the module that owns the contract** — `EventContractTest` round-trips
   every event type, so a record change that breaks serialisation fails the `oms-common` build
   rather than five services at runtime.

## Alternatives considered

**Avro + Confluent Schema Registry.** The stronger engineering answer, and it should be said
plainly that this ADR trades some safety for simplicity. The registry enforces compatibility
at publish time, which is a machine check where this decision substitutes a written rule and a
test. Rejected here because: it adds a stateful service that must be highly available (if the
registry is down, producers cannot serialise), it adds the Confluent platform image to
`docker-compose` — meaningfully slowing the one-command local stack this project promises — and
it moves the event definitions from readable Java records into generated classes, which makes
the contract harder for a reviewer to read. For a portfolio project whose events are read by
humans evaluating it, that last point is not trivial. **It is the first thing I would change
if this went to production**, and the ADR says so on purpose.

**Protobuf without a registry.** Compact and schema-first, with decent built-in compatibility
rules. Rejected because the generated-code build step buys little without a registry enforcing
compatibility, and because `kafka-console-consumer` output stops being readable — which
matters more than it sounds like during incident response.

**JSON with no versioning discipline at all.** What most projects actually do. Rejected: it
works until the first field rename, at which point it fails in production, on a partition, at
the worst possible time.

## Consequences

**Good**

- Zero extra infrastructure. `docker-compose up` brings up one broker, no registry.
- Events are human-readable in `kafka-console-consumer`, in DLTs, and in logs. During an
  incident this is worth a surprising amount.
- Event definitions are the Java records themselves — one source of truth, readable by anyone,
  no code generation step in the build.
- Breaking changes are migratable without a lock-step deploy: the producer dual-writes `v1`
  and `v2` and consumers move at their own pace.

**Costs, accepted**

- Payloads are roughly 3–5× larger than Avro. Irrelevant for orders and trades (tens to
  hundreds per second). It *is* relevant for `marketdata.ticks`, the only high-volume topic —
  which is why that topic has one-hour retention and why the streaming path to clients does
  not proxy raw events.
- Compatibility is enforced by convention plus a test, not by the broker. A determined
  developer can still ship a breaking change. The mitigation is that the rules are written
  down and the contract test is in the shared module's build.
- No machine-generated documentation of the schema. Mitigated by the event catalogue in
  `docs/kafka-event-design.md` §3.

## Notes for the C++ reader

This is the MessagePack-versus-hand-rolled-struct decision, with one difference that matters.
A packed struct over ZeroMQ gives you no evolution story at all: add a field and every peer
must be rebuilt and redeployed together, because the wire format *is* the memory layout. Both
Avro and JSON are self-describing enough to evolve, so the question shifts from *can we change
this* to *who checks that the change is safe* — a registry (machine) or a rule plus a test
(process). Choosing the latter is a legitimate tradeoff at this scale, but it is a tradeoff,
and pretending otherwise is the answer that loses an interview.
