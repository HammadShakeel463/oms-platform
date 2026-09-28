package com.oms.order.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.event.DomainEvent;
import com.oms.order.domain.OutboxEvent;
import com.oms.order.repository.OutboxRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Appends an event to the transactional outbox.
 *
 * <p>Every call here must run inside the caller's transaction. There is deliberately no
 * {@code @Transactional} annotation on this class: it participates in whatever transaction
 * the caller opened, which is the entire point. Annotating it with
 * {@code REQUIRES_NEW} would open a second transaction that could commit while the business
 * change rolled back - announcing an order that does not exist.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public OutboxEvent enqueue(String topic, DomainEvent event, String aggregateId) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Serialising an event this service defines cannot fail for data reasons; if it
            // does, the contract is broken and no amount of retrying fixes it. Fail the
            // transaction so the business change rolls back with it.
            throw new IllegalStateException(
                    "Could not serialise " + event.getClass().getSimpleName(), e);
        }

        OutboxEvent row = new OutboxEvent(
                UUID.fromString(event.eventId()),
                aggregateId,
                event.getClass().getSimpleName(),
                topic,
                event.partitionKey(),
                payload,
                MDC.get("traceId"));

        return outboxRepository.save(row);
    }

    /** Event ids are UUIDs so the outbox can store them in a UUID column. */
    public static String newEventId() {
        return UUID.randomUUID().toString();
    }
}
