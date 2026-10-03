package com.oms.order.messaging;

import com.oms.order.config.OrderProperties;
import com.oms.order.domain.OutboxEvent;
import com.oms.order.repository.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The outbox publisher: the half of the transactional outbox that is not in the transaction.
 *
 * <p>The behaviour worth pinning down is what happens when the broker does <b>not</b> take a
 * record. The ordering of "block for the ack, then stamp published_at" is the whole durability
 * argument - stamping first would mean a broker that never accepted the record leaves a row
 * marked published, and the order is silently never worked while the API has already returned 201.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-09-28T09:15:00Z");

    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, String>> sent;

    private MeterRegistry meterRegistry;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = new OutboxPublisher(
                outboxRepository,
                kafkaTemplate,
                new OrderProperties(new BigDecimal("0.05"), 200, Duration.ofDays(3)),
                meterRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("an acknowledged row is stamped published and counted")
    void acknowledgedRowIsMarkedPublished() {
        OutboxEvent event = outboxRow("oms.orders.accepted.v1", "HBL");
        when(outboxRepository.claimUnpublishedBatch(anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(acknowledged());

        publisher.publishPending();

        assertThat(event.isPublished()).isTrue();
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
        assertThat(event.getAttempts()).isZero();
        verify(outboxRepository).saveAll(List.of(event));
        assertThat(meterRegistry.counter("oms.outbox.published").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a row the broker refused is left unpublished, so the next poll retries it")
    void refusedRowStaysUnpublished() {
        OutboxEvent event = outboxRow("oms.orders.accepted.v1", "HBL");
        when(outboxRepository.claimUnpublishedBatch(anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        publisher.publishPending();

        assertThat(event.isPublished())
                .as("published_at must stay null or the order is never worked and nobody is told")
                .isFalse();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("broker down");
        verify(outboxRepository).saveAll(List.of(event));
        assertThat(meterRegistry.counter("oms.outbox.failed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("oms.outbox.published").count()).isZero();
    }

    @Test
    @DisplayName("one failure in a batch does not block the rows around it")
    void aFailureDoesNotPoisonTheBatch() {
        OutboxEvent first = outboxRow("oms.orders.accepted.v1", "HBL");
        OutboxEvent broken = outboxRow("oms.orders.accepted.v1", "ENGRO");
        OutboxEvent last = outboxRow("oms.orders.cancel-requests.v1", "LUCK");
        when(outboxRepository.claimUnpublishedBatch(anyInt()))
                .thenReturn(List.of(first, broken, last));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(acknowledged())
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("record too large")))
                .thenReturn(acknowledged());

        publisher.publishPending();

        assertThat(first.isPublished()).isTrue();
        assertThat(broken.isPublished()).isFalse();
        assertThat(last.isPublished())
                .as("a single bad row must not stall everything behind it")
                .isTrue();
        assertThat(meterRegistry.counter("oms.outbox.published").count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter("oms.outbox.failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the record carries the topic, the partition key and the correlation headers")
    void recordCarriesKeyAndHeaders() {
        OutboxEvent event = outboxRow("oms.orders.accepted.v1", "HBL");
        when(outboxRepository.claimUnpublishedBatch(anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(sent.capture())).thenReturn(acknowledged());

        publisher.publishPending();

        ProducerRecord<String, String> record = sent.getValue();
        assertThat(record.topic()).isEqualTo("oms.orders.accepted.v1");
        assertThat(record.key())
                .as("the key is the symbol, which is what gives the engine one writer per book")
                .isEqualTo("HBL");
        assertThat(record.value()).isEqualTo(event.getPayload());
        assertThat(header(record, "oms-event-id")).isEqualTo(event.getEventId().toString());
        assertThat(header(record, "oms-event-type")).isEqualTo("OrderAcceptedEvent");
        assertThat(header(record, "oms-trace-id")).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("a row with no trace id omits the header rather than sending a null value")
    void absentTraceIdOmitsTheHeader() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "order-1", "OrderAcceptedEvent",
                "oms.orders.accepted.v1", "HBL", "{}", null);
        when(outboxRepository.claimUnpublishedBatch(anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(sent.capture())).thenReturn(acknowledged());

        publisher.publishPending();

        assertThat(sent.getValue().headers().lastHeader("oms-trace-id")).isNull();
    }

    @Test
    @DisplayName("an empty claim does no work at all - no send, no save, no counter movement")
    void emptyBatchIsANoOp() {
        when(outboxRepository.claimUnpublishedBatch(anyInt())).thenReturn(List.of());

        publisher.publishPending();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(outboxRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("the publisher claims at most the configured batch size")
    void claimsTheConfiguredBatchSize() {
        OutboxPublisher small = new OutboxPublisher(outboxRepository, kafkaTemplate,
                new OrderProperties(new BigDecimal("0.05"), 25, Duration.ofDays(3)),
                meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
        when(outboxRepository.claimUnpublishedBatch(25)).thenReturn(List.of());

        small.publishPending();

        verify(outboxRepository).claimUnpublishedBatch(25);
    }

    @Test
    @DisplayName("purge deletes published rows older than the retention window, measured from the clock")
    void purgeUsesTheRetentionWindow() {
        when(outboxRepository.deletePublishedBefore(NOW.minus(Duration.ofDays(3)))).thenReturn(7);

        publisher.purgePublished();

        verify(outboxRepository).deletePublishedBefore(NOW.minus(Duration.ofDays(3)));
    }

    @Test
    @DisplayName("the backlog gauge reports the unpublished count, and NaN when the database is unreachable")
    void backlogGaugeDegradesToNaN() {
        when(outboxRepository.countByPublishedAtIsNull()).thenReturn(4L);
        assertThat(publisher.backlog()).isEqualTo(4.0);

        when(outboxRepository.countByPublishedAtIsNull())
                .thenThrow(new IllegalStateException("no connection"));
        assertThat(publisher.backlog())
                .as("NaN is absent in Prometheus; 0 would be a plausible wrong number that "
                        + "silences the most valuable alert in the platform")
                .isNaN();
    }

    @Test
    @DisplayName("an over-long broker error is truncated rather than overflowing the column")
    void longErrorIsTruncated() {
        OutboxEvent event = outboxRow("oms.orders.accepted.v1", "HBL");
        when(outboxRepository.claimUnpublishedBatch(anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new KafkaException("x".repeat(5_000))));

        publisher.publishPending();

        assertThat(event.getLastError()).hasSize(2_000);
    }

    private static OutboxEvent outboxRow(String topic, String partitionKey) {
        return new OutboxEvent(UUID.randomUUID(), "order-1", "OrderAcceptedEvent",
                topic, partitionKey, "{\"symbol\":\"" + partitionKey + "\"}", "trace-abc");
    }

    private static CompletableFuture<SendResult<String, String>> acknowledged() {
        return CompletableFuture.completedFuture(null);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
