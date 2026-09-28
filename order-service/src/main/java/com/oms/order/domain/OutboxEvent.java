package com.oms.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A row in the transactional outbox (ADR 0004).
 *
 * <p>Written in the same transaction as the business change it announces. A separate
 * poller publishes it to Kafka and stamps {@code publishedAt}. That is what makes
 * "persist the order" and "tell the matching engine" atomic without a distributed
 * transaction.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Also the Kafka-level idempotency key that consumers deduplicate on. */
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_id", nullable = false, length = 64, updatable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 128, updatable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 64, updatable = false)
    private String partitionKey;

    /**
     * The serialised event. {@code @JdbcTypeCode(SqlTypes.JSON)} maps a Java String to a
     * PostgreSQL {@code jsonb} column - the Hibernate 6 way, replacing the third-party
     * type libraries earlier projects needed. Storing JSON rather than a Java-serialised
     * blob means the outbox stays readable and queryable during an incident.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "trace_id", length = 64, updatable = false)
    private String traceId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID eventId, String aggregateId, String eventType, String topic,
                       String partitionKey, String payload, String traceId) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.traceId = traceId;
        this.attempts = 0;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error != null && error.length() > 2000
                ? error.substring(0, 2000)
                : error;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
