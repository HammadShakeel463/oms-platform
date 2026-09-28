package com.oms.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Consumer-side deduplication marker, written in the same transaction as the effect it
 * guards. That co-transaction is the whole point: if the effect commits, the marker
 * commits; if the handler crashes, neither does, and the replay is clean.
 *
 * <p>Used for events whose effect has no natural unique key of its own. Where a natural
 * key exists - as for fills, keyed {@code (tradeId, orderId)} - the constraint on the real
 * table is a better guard and this table is not needed.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @EmbeddedId
    private Key id;

    @Column(name = "processed_at", insertable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(String consumer, String eventId) {
        this.id = new Key(consumer, eventId);
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "consumer", nullable = false, length = 64)
        private String consumer;

        @Column(name = "event_id", nullable = false, length = 64)
        private String eventId;

        protected Key() {
        }

        public Key(String consumer, String eventId) {
            this.consumer = consumer;
            this.eventId = eventId;
        }

        public String getConsumer() {
            return consumer;
        }

        public String getEventId() {
            return eventId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(consumer, k.consumer)
                    && Objects.equals(eventId, k.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumer, eventId);
        }
    }

    public Key getId() {
        return id;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
