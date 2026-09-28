package com.oms.common.event;

import java.time.Instant;

/**
 * Root of the published event contract.
 *
 * <p>A {@code sealed interface} over {@code record} implementations is the Java 17 way to
 * express a closed sum type - the same thing a {@code std::variant} of PODs gives you in
 * C++, except the compiler can check exhaustiveness in a {@code switch} instead of making
 * you write a visitor. Sealing is also a design statement: this set of events is the
 * platform contract, and adding one is a deliberate, reviewable act rather than a new
 * class appearing somewhere in a service.
 *
 * <p>Wire format is JSON (see docs/adr/0003-json-events-no-schema-registry.md).
 * Each topic carries exactly one concrete type, so consumers deserialize to that type
 * directly - no {@code @JsonTypeInfo} discriminator, no polymorphic deserialization.
 */
public sealed interface DomainEvent
        permits OrderAcceptedEvent,
                OrderCancelRequestedEvent,
                OrderCancelConfirmedEvent,
                OrderLifecycleEvent,
                TradeExecutedEvent,
                MarketTickEvent {

    /**
     * Producer-assigned unique id. Consumers deduplicate on this, which is what makes
     * at-least-once delivery safe (see docs/kafka-event-design.md).
     */
    String eventId();

    /** Business time the event happened, as seen by the producer. */
    Instant occurredAt();

    /**
     * Contract version of this payload. Bumped only for breaking changes; additive
     * optional fields keep the same version.
     */
    int schemaVersion();

    /**
     * The Kafka message key. Ordering is guaranteed only within a partition, so the
     * key choice IS the ordering guarantee - every event type states its own here
     * rather than leaving it to the producer call site.
     */
    String partitionKey();
}
