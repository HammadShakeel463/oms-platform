package com.oms.order.messaging;

import com.oms.order.config.OrderProperties;
import com.oms.order.domain.OutboxEvent;
import com.oms.order.repository.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Drains the transactional outbox to Kafka (ADR 0004).
 *
 * <p>The contract: every row is published <em>at least once</em>, in creation order per
 * partition key. Never exactly once - the process can publish a record and die before
 * stamping {@code published_at}, and the next poll will publish it again. That is fine and
 * expected, because every consumer in the platform is idempotent. Attempting exactly-once
 * here would require a distributed transaction between PostgreSQL and Kafka, which is the
 * thing the outbox pattern exists to avoid.
 *
 * <p><b>Why polling rather than change-data-capture.</b> Debezium tailing the WAL is the
 * lower-latency, higher-machinery answer, and at real volume it is the right one. Polling
 * costs one indexed query per interval, needs no connector to operate, and its latency
 * (bounded by the poll interval, typically single-digit milliseconds here) is far below the
 * round trip the order already pays. The partial index on {@code published_at IS NULL} keeps
 * the query proportional to the backlog rather than to the table.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OrderProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           OrderProperties properties,
                           MeterRegistry meterRegistry,
                           Clock clock) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        meterRegistry.gauge("oms.outbox.backlog", this, OutboxPublisher::backlog);
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}. Fixed rate schedules the next run a fixed
     * interval after the previous <em>start</em>, so a slow batch causes runs to overlap
     * and pile up - the scheduling equivalent of unbounded queue growth. Fixed delay
     * measures from the previous completion, which self-throttles under load.
     *
     * <p>The whole batch runs in one transaction: the rows are claimed with
     * {@code FOR UPDATE SKIP LOCKED} and the lock is held until commit, so no other replica
     * can claim the same rows while this one is publishing them.
     */
    @Scheduled(fixedDelayString = "${oms.order.outbox-poll-interval:200}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.claimUnpublishedBatch(properties.outboxBatchSize());
        if (batch.isEmpty()) {
            return;
        }

        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    event.getTopic(), event.getPartitionKey(), event.getPayload());
            record.headers().add("oms-event-id",
                    event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add("oms-event-type",
                    event.getEventType().getBytes(StandardCharsets.UTF_8));
            if (event.getTraceId() != null) {
                record.headers().add("oms-trace-id",
                        event.getTraceId().getBytes(StandardCharsets.UTF_8));
            }
            futures.add(kafkaTemplate.send(record));
        }

        Instant now = clock.instant();
        int published = 0;
        for (int i = 0; i < batch.size(); i++) {
            OutboxEvent event = batch.get(i);
            try {
                // Block until the broker acknowledges. The send is only durable once the
                // ack arrives, and stamping published_at before that would lose the event
                // if the broker never took it.
                futures.get(i).get(30, TimeUnit.SECONDS);
                event.markPublished(now);
                published++;
            } catch (Exception e) {
                // Leave published_at null: the next poll retries this row. The row is not
                // lost and nothing downstream saw a partial batch.
                event.markFailed(e.getMessage());
                log.warn("Outbox row {} (event {}) failed to publish, attempt {}: {}",
                        event.getId(), event.getEventId(), event.getAttempts(), e.toString());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        outboxRepository.saveAll(batch);
        meterRegistry.counter("oms.outbox.published").increment(published);
        if (published < batch.size()) {
            meterRegistry.counter("oms.outbox.failed").increment(batch.size() - published);
        }
        log.debug("Outbox: published {} of {} claimed rows", published, batch.size());
    }

    /**
     * Housekeeping. Without it the outbox grows for ever and the partial index degrades as
     * the table does.
     */
    @Scheduled(cron = "${oms.order.outbox-cleanup-cron:0 */15 * * * *}")
    @Transactional
    public void purgePublished() {
        Instant cutoff = clock.instant().minus(properties.outboxRetention());
        int removed = outboxRepository.deletePublishedBefore(cutoff);
        if (removed > 0) {
            log.info("Purged {} published outbox rows older than {}", removed, cutoff);
        }
    }

    /** Backlog size, exported as a gauge. A rising backlog means Kafka is unreachable. */
    public double backlog() {
        try {
            return outboxRepository.countByPublishedAtIsNull();
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
