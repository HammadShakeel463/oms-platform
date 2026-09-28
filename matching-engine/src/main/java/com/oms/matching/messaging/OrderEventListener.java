package com.oms.matching.messaging;

import com.oms.common.event.OrderAcceptedEvent;
import com.oms.common.event.OrderCancelRequestedEvent;
import com.oms.common.event.Topics;
import com.oms.matching.book.NewOrder;
import com.oms.matching.engine.MatchingEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The engine's only input.
 *
 * <h2>Batch listeners, and why</h2>
 *
 * <p>These are batch listeners ({@code List<ConsumerRecord<...>>}) rather than record
 * listeners. Three reasons, in order of importance:
 *
 * <ol>
 *   <li><b>The depth snapshot is published once per batch.</b> Rebuilding an immutable
 *       snapshot is the only deliberate allocation in the book; doing it per order would put
 *       it back on the per-order cost. A batch of 200 orders pays for one snapshot.</li>
 *   <li><b>Sends are awaited once per batch.</b> One broker round trip for the whole batch
 *       instead of one per fill - see {@link EventPublisher}.</li>
 *   <li>Fewer poll-loop iterations and less per-record framework overhead.</li>
 * </ol>
 *
 * <p>What batching does <b>not</b> change is ordering: records within a partition arrive in
 * the batch in offset order and are processed in that order, so price-time priority is intact.
 * That is the property the whole partitioning design exists to protect.
 *
 * <h2>The single-writer guarantee</h2>
 *
 * <p>The container runs one thread per assigned partition, and every message for a symbol
 * keys to one partition, so a book is only ever touched by one thread. Nothing in this class
 * or in the book takes a lock. See {@link MatchingEngine} and ADR 0005.
 *
 * <p>The accepted-orders and cancel-requests topics are consumed in <b>one consumer group</b>
 * with the same key, so a symbol's orders and its cancels land on the same instance - and,
 * because the partition assignment is per topic-partition, on the same thread. Two groups
 * would allow a cancel to be processed by a thread that does not own the book.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final MatchingEngine engine;
    private final EventPublisher publisher;
    private final MeterRegistry meterRegistry;
    private final Timer matchTimer;

    public OrderEventListener(MatchingEngine engine, EventPublisher publisher,
                              MeterRegistry meterRegistry) {
        this.engine = engine;
        this.publisher = publisher;
        this.meterRegistry = meterRegistry;
        this.matchTimer = Timer.builder("oms.engine.match")
                .description("Time to match one order inside the book")
                .publishPercentiles(0.5, 0.95, 0.99, 0.999)
                // A histogram, not just a running mean: the mean of a latency distribution
                // tells you almost nothing, and p99 cannot be derived from it afterwards.
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = Topics.ORDERS_ACCEPTED,
            groupId = "${oms.engine.consumer-group:matching-engine}",
            containerFactory = "acceptedOrderListenerFactory",
            batch = "true")
    public void onAcceptedOrders(List<ConsumerRecord<String, OrderAcceptedEvent>> records) {
        EventPublisher.BatchSink sink = publisher.newBatch();
        Set<String> touched = new HashSet<>();

        for (ConsumerRecord<String, OrderAcceptedEvent> record : records) {
            OrderAcceptedEvent event = record.value();
            if (event == null) {
                // ErrorHandlingDeserializer already routed the bad payload; nothing to match.
                continue;
            }

            NewOrder order = new NewOrder(
                    event.orderId(), event.accountId(), event.side(), event.orderType(),
                    event.timeInForce(), event.limitPriceTicks(), event.quantity());

            long start = System.nanoTime();
            boolean processed = engine.submit(event.symbol(), order, sink);
            matchTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);

            if (processed) {
                touched.add(event.symbol());
            } else {
                meterRegistry.counter("oms.engine.duplicates.ignored").increment();
            }
        }

        // Publish events first: the offset must not advance past a batch whose fills never
        // reached the broker.
        sink.awaitAll();
        touched.forEach(engine::publishSnapshot);

        if (!records.isEmpty()) {
            log.debug("Matched {} orders across {} symbols: {} trades, {} cancels",
                    records.size(), touched.size(), sink.tradeCount(), sink.cancelCount());
        }
    }

    @KafkaListener(
            topics = Topics.ORDERS_CANCEL_REQUESTS,
            groupId = "${oms.engine.consumer-group:matching-engine}",
            containerFactory = "cancelRequestListenerFactory",
            batch = "true")
    public void onCancelRequests(List<ConsumerRecord<String, OrderCancelRequestedEvent>> records) {
        EventPublisher.BatchSink sink = publisher.newBatch();
        Set<String> touched = new HashSet<>();

        for (ConsumerRecord<String, OrderCancelRequestedEvent> record : records) {
            OrderCancelRequestedEvent event = record.value();
            if (event == null) {
                continue;
            }
            engine.cancel(event.symbol(), event.orderId(), event.accountId(), sink);
            touched.add(event.symbol());
        }

        sink.awaitAll();
        touched.forEach(engine::publishSnapshot);
    }
}
