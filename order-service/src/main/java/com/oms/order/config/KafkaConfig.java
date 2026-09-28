package com.oms.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.error.IllegalStateTransitionException;
import com.oms.common.event.OrderCancelConfirmedEvent;
import com.oms.common.event.TradeExecutedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka wiring. The settings here are the ones described in
 * {@code docs/kafka-event-design.md} §4-§5, made concrete.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;

    public KafkaConfig(KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
    }

    // =================================================================================
    //  Producer
    // =================================================================================

    /**
     * The outbox publisher sends already-serialised JSON, so the value serialiser is
     * {@code StringSerializer} - serialising twice would be pure waste.
     *
     * <p>The three settings that matter:
     * <ul>
     *   <li>{@code acks=all} - the leader waits for the in-sync replicas. Combined with
     *       {@code min.insync.replicas=2} on the broker, an acknowledged write survives
     *       losing the leader. {@code acks=all} on its own, with ISR of 1, is a durability
     *       illusion.</li>
     *   <li>{@code enable.idempotence=true} - the producer tags records with a sequence
     *       number so a retried send is deduplicated by the broker. Without it, a retry
     *       after a timed-out-but-successful send silently duplicates the record.</li>
     *   <li>{@code max.in.flight=5} - with idempotence on, the broker reorders by sequence,
     *       so pipelining five requests keeps throughput without breaking per-partition
     *       ordering. Without idempotence this same setting would allow a retried record to
     *       land after a later one.</li>
     * </ul>
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    /**
     * A second template purely for dead-letter publishing.
     *
     * <p>When {@code ErrorHandlingDeserializer} fails, the record value is the original raw
     * {@code byte[]} - there is no object to serialise, and that is exactly the payload you
     * want preserved in the DLT for forensics. A {@code String} serialiser would reject it.
     * {@code DelegatingByTypeSerializer} picks the serialiser from the runtime type, so
     * both a deserialisation failure (bytes) and a handler failure (a String value) land in
     * the DLT intact.
     */
    @Bean
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        DefaultKafkaProducerFactory<Object, Object> pf = new DefaultKafkaProducerFactory<>(props);
        pf.setKeySerializer(new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                String.class, new StringSerializer())));
        pf.setValueSerializer(new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                String.class, new StringSerializer())));
        return new KafkaTemplate<>(pf);
    }

    // =================================================================================
    //  Consumers
    // =================================================================================

    /**
     * Builds a listener container factory for one concrete event type.
     *
     * <p>Each topic carries exactly one type (ADR 0003), so the deserialiser is told that
     * type directly and type headers are switched off. Trusting a type name that arrived
     * over the wire is how a deserialisation gadget gets instantiated; here the consumer
     * decides what it is reading, and a producer cannot influence it.
     *
     * <p>{@code ErrorHandlingDeserializer} wraps the real one so that a malformed payload
     * becomes a failed record the error handler can route to the DLT, instead of an
     * exception inside the poll loop that the container can only retry forever.
     */
    private <T> ConcurrentKafkaListenerContainerFactory<String, T> listenerFactory(
            Class<T> type, DefaultErrorHandler errorHandler, int concurrency) {

        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);

        JsonDeserializer<T> valueDeserializer = new JsonDeserializer<>(type, objectMapper, false);
        valueDeserializer.setUseTypeHeaders(false);

        ConsumerFactory<String, T> consumerFactory = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(),
                new ErrorHandlingDeserializer<>(valueDeserializer));

        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(concurrency);
        // Commit the offset only after the handler returns successfully. Committing before
        // the work is what turns at-least-once into at-most-once and silently loses fills.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TradeExecutedEvent>
    tradeListenerFactory(DefaultErrorHandler errorHandler) {
        return listenerFactory(TradeExecutedEvent.class, errorHandler, 3);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderCancelConfirmedEvent>
    executionReportListenerFactory(DefaultErrorHandler errorHandler) {
        return listenerFactory(OrderCancelConfirmedEvent.class, errorHandler, 3);
    }

    // =================================================================================
    //  Error handling
    // =================================================================================

    /**
     * Blocking retry with exponential backoff, then the dead-letter topic.
     *
     * <p><b>Why blocking rather than {@code @RetryableTopic}.</b> Non-blocking retry
     * republishes the failed record to a retry topic and carries on. That preserves
     * throughput and breaks ordering - a retried fill could be applied after a later fill
     * for the same order, and the average price would be wrong. Ordering is the property
     * this consumer exists to preserve, so head-of-line blocking is the lesser evil, and
     * the backoff budget bounds it.
     *
     * <p><b>Why the classification matters more than the count.</b> A malformed payload or
     * an illegal transition will fail identically on every attempt; retrying it five times
     * only delays the DLT while blocking the partition. Those go straight through. Transient
     * failures - an optimistic lock collision, a dropped connection - are exactly what
     * backoff is for.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> deadLetterKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> {
                    log.error("Routing {}-{}@{} to the dead-letter topic: {}",
                            record.topic(), record.partition(), record.offset(),
                            exception.getMessage());
                    return new org.apache.kafka.common.TopicPartition(
                            record.topic() + com.oms.common.event.Topics.DLT_SUFFIX,
                            record.partition());
                });

        ExponentialBackOff backOff = new ExponentialBackOff(100L, 2.0);
        backOff.setMaxElapsedTime(30_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                IllegalStateTransitionException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class);
        handler.setAckAfterHandle(true);
        return handler;
    }
}
