package com.oms.order.service;

import com.oms.common.domain.OrderStatus;
import com.oms.common.event.OrderLifecycleEvent;
import com.oms.common.event.Topics;
import com.oms.order.domain.OrderAuditEntity;
import com.oms.order.domain.OrderEntity;
import com.oms.order.messaging.OutboxWriter;
import com.oms.order.repository.OrderAuditRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * The order state machine: the single place a status ever changes.
 *
 * <p>One call does three things that must happen together or not at all:
 * <ol>
 *   <li>validate and apply the transition on the entity (the transition table in
 *       {@code OrderStatus} decides, not this class);</li>
 *   <li>append an immutable {@code order_audit} row;</li>
 *   <li>append an {@code OrderLifecycleEvent} to the outbox.</li>
 * </ol>
 *
 * <p>All three are ordinary JPA writes inside the caller's transaction, so they commit
 * together. No listener, no event bus, no "eventually the audit row appears". If the audit
 * trail could be written separately from the state change, it would eventually disagree
 * with it, and an audit trail that can disagree with reality is worse than none.
 *
 * <p>Note the absence of {@code @Transactional} here too - see {@link OutboxWriter}. This
 * service is always called from inside {@link OrderService}, whose method owns the
 * transaction boundary. Having one clear transactional entry point, rather than annotations
 * scattered down the call stack, is what makes the boundary reviewable.
 */
@Service
public class OrderLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleService.class);

    private final OrderAuditRepository auditRepository;
    private final OutboxWriter outboxWriter;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public OrderLifecycleService(OrderAuditRepository auditRepository,
                                 OutboxWriter outboxWriter,
                                 MeterRegistry meterRegistry,
                                 Clock clock) {
        this.auditRepository = auditRepository;
        this.outboxWriter = outboxWriter;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Journals the birth of an order: the one audit row whose {@code previousStatus} is
     * null. A transition table cannot express "came into existence", so creation gets its
     * own method rather than a fake NEW -> NEW transition.
     */
    public OrderAuditEntity recordCreation(OrderEntity order, String actor) {
        Instant now = clock.instant();
        OrderAuditEntity audit = auditRepository.save(new OrderAuditEntity(
                order.getOrderId(), 1, null, OrderStatus.NEW, "order accepted by API",
                0L, order.getQuantity(), null, actor, MDC.get("traceId"), now));

        outboxWriter.enqueue(Topics.ORDERS_LIFECYCLE, new OrderLifecycleEvent(
                OutboxWriter.newEventId(), now, OrderLifecycleEvent.CURRENT_SCHEMA_VERSION,
                order.getOrderId(), order.getAccountId(), order.getSymbol(),
                null, OrderStatus.NEW, "order accepted by API",
                0L, order.getQuantity(), com.oms.common.money.Ticks.NO_PRICE, 1),
                order.getOrderId().toString());

        meterRegistry.counter("oms.order.created", "symbol", order.getSymbol()).increment();
        return audit;
    }

    /**
     * Applies a transition, journals it and announces it.
     *
     * @param actor who caused it: a user id, or the name of the consumer applying an event
     * @throws com.oms.common.error.IllegalStateTransitionException if the move is illegal
     */
    public OrderAuditEntity transition(OrderEntity order, OrderStatus target,
                                       String reason, String actor) {
        OrderStatus previous = order.getStatus();
        order.transitionTo(target, target == OrderStatus.REJECTED ? reason : null);

        Instant now = clock.instant();
        int seq = auditRepository.currentSeq(order.getOrderId()) + 1;

        OrderAuditEntity audit = auditRepository.save(new OrderAuditEntity(
                order.getOrderId(), seq, previous, target, reason,
                order.getFilledQuantity(), order.leavesQuantity(), order.getAvgPrice(),
                actor, MDC.get("traceId"), now));

        outboxWriter.enqueue(Topics.ORDERS_LIFECYCLE, new OrderLifecycleEvent(
                OutboxWriter.newEventId(),
                now,
                OrderLifecycleEvent.CURRENT_SCHEMA_VERSION,
                order.getOrderId(),
                order.getAccountId(),
                order.getSymbol(),
                previous,
                target,
                reason,
                order.getFilledQuantity(),
                order.leavesQuantity(),
                order.avgPriceTicks(),
                seq), order.getOrderId().toString());

        meterRegistry.counter("oms.order.transitions",
                "from", previous.name(), "to", target.name()).increment();

        log.debug("Order {} {} -> {} (seq {}, actor {}, reason {})",
                order.getOrderId(), previous, target, seq, actor, reason);

        return audit;
    }
}
