package com.oms.matching.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.domain.Side;
import com.oms.common.event.CancelReason;
import com.oms.common.event.OrderCancelConfirmedEvent;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.event.Topics;
import com.oms.matching.book.MatchListener;
import com.oms.matching.engine.TradeIds;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Turns what the book reports into Kafka records.
 *
 * <p>This class is the allocation boundary. The book pushes primitives so that matching
 * allocates nothing; here, where a record genuinely has to go on the wire, one event object
 * per fill is unavoidable and fine.
 *
 * <h2>Why sends are buffered and awaited per batch</h2>
 *
 * <p>The engine holds no database. Its durability story is "the books can be rebuilt by
 * replaying the partition" - and that only works if the Kafka offset is never committed for
 * a batch whose trades did not reach the broker. If the offset advanced past an order whose
 * fill was lost, a rebuild would not reproduce it: the input record would be gone too.
 *
 * <p>So each batch gets a {@link BatchSink}, which collects the send futures, and the
 * listener calls {@link BatchSink#awaitAll()} before returning. Only then does Spring commit
 * the offset. A send failure throws, the batch is retried, and the replay re-emits trades
 * with identical ids (see {@link TradeIds}) so the retry is harmless.
 *
 * <p>Awaiting per batch rather than per record is the point: one wait for a hundred records
 * costs one broker round trip, and the producer pipelines them. Awaiting per record would
 * serialise the engine behind network latency.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 30;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public EventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                          ObjectMapper objectMapper,
                          MeterRegistry meterRegistry,
                          Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** One sink per consumed batch. Not thread safe - it belongs to one writer thread. */
    public BatchSink newBatch() {
        return new BatchSink();
    }

    public final class BatchSink implements MatchListener {

        private final List<CompletableFuture<?>> pending = new ArrayList<>(64);
        private int trades;
        private int cancels;

        @Override
        public void onTrade(String symbol, long priceTicks, long quantity, Side aggressorSide,
                            UUID buyOrderId, String buyAccountId,
                            UUID sellOrderId, String sellAccountId, long sequence) {

            TradeExecutedEvent event = new TradeExecutedEvent(
                    UUID.randomUUID().toString(),
                    clock.instant(),
                    TradeExecutedEvent.CURRENT_SCHEMA_VERSION,
                    // Deterministic: a replay produces the same id, so downstream dedupe works.
                    TradeIds.of(symbol, sequence),
                    symbol,
                    priceTicks,
                    quantity,
                    aggressorSide,
                    buyOrderId, buyAccountId,
                    sellOrderId, sellAccountId,
                    sequence);

            send(Topics.TRADES_EXECUTED, symbol, event);
            trades++;
        }

        @Override
        public void onCancelled(String symbol, UUID orderId, String accountId,
                                CancelReason reason, long quantity, long sequence) {

            OrderCancelConfirmedEvent event = new OrderCancelConfirmedEvent(
                    UUID.randomUUID().toString(),
                    clock.instant(),
                    OrderCancelConfirmedEvent.CURRENT_SCHEMA_VERSION,
                    orderId,
                    symbol,
                    accountId,
                    reason,
                    quantity,
                    sequence);

            send(Topics.EXECUTION_REPORTS, symbol, event);
            cancels++;
        }

        private void send(String topic, String key, Object event) {
            String payload;
            try {
                payload = objectMapper.writeValueAsString(event);
            } catch (JsonProcessingException e) {
                // Serialising an event this service defines cannot fail for data reasons. If
                // it does, the contract is broken and retrying will not mend it.
                throw new IllegalStateException(
                        "Could not serialise " + event.getClass().getSimpleName(), e);
            }
            pending.add(kafkaTemplate.send(new ProducerRecord<>(topic, key, payload)));
        }

        /**
         * Blocks until every send in this batch is acknowledged by the broker.
         *
         * @throws IllegalStateException if any send failed, so the batch is retried and the
         *                               offset is not committed
         */
        public void awaitAll() {
            if (pending.isEmpty()) {
                return;
            }
            try {
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while publishing engine events", e);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to publish " + pending.size() + " engine events; batch will be retried", e);
            }

            meterRegistry.counter("oms.engine.trades.published").increment(trades);
            meterRegistry.counter("oms.engine.cancels.published").increment(cancels);
            log.debug("Published {} trades and {} cancel reports", trades, cancels);
        }

        public int tradeCount() {
            return trades;
        }

        public int cancelCount() {
            return cancels;
        }
    }

    /** Last-resort publication for a single event outside a batch. Used by admin endpoints. */
    public void publishNow(String topic, String key, Object event) {
        BatchSink sink = new BatchSink();
        sink.send(topic, key, event);
        sink.awaitAll();
    }

    Instant now() {
        return clock.instant();
    }
}
