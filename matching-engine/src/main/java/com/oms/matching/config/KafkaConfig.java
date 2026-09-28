package com.oms.matching.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.event.OrderAcceptedEvent;
import com.oms.common.event.OrderCancelRequestedEvent;
import com.oms.common.event.Topics;
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
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;
    private final EngineProperties engineProperties;

    public KafkaConfig(KafkaProperties kafkaProperties, ObjectMapper objectMapper,
                       EngineProperties engineProperties) {
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
        this.engineProperties = engineProperties;
    }

    // =================================================================================
    //  Producer - trades and cancel reports
    // =================================================================================

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        // A small linger deliberately: the engine publishes a burst of fills per batch, and
        // 2ms of batching turns dozens of one-record requests into one. Zero linger would
        // make every fill its own round trip and cost far more than 2ms.
        props.put(ProducerConfig.LINGER_MS_CONFIG, 2);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        DefaultKafkaProducerFactory<Object, Object> pf = new DefaultKafkaProducerFactory<>(props);
        DelegatingByTypeSerializer serializer = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                String.class, new StringSerializer()));
        pf.setKeySerializer(serializer);
        pf.setValueSerializer(serializer);
        return new KafkaTemplate<>(pf);
    }

    // =================================================================================
    //  Consumers - batch listeners
    // =================================================================================

    /**
     * Builds a batch listener container factory for one event type.
     *
     * <p>{@code concurrency} is the number of consumer threads, and therefore the number of
     * books that can match in parallel. It is capped by the partition count (12): asking for
     * more threads than partitions just leaves threads idle, because a partition is assigned
     * to at most one consumer in a group.
     *
     * <p>{@code MAX_POLL_RECORDS} is the batch size the listener sees. Larger batches amortise
     * the snapshot rebuild and the broker round trip better, but they also lengthen the time
     * between offset commits, which is the amount of replay a crash costs. 200 is a
     * deliberate middle.
     */
    private <T> ConcurrentKafkaListenerContainerFactory<String, T> batchFactory(
            Class<T> type, DefaultErrorHandler errorHandler) {

        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // earliest, because the book is rebuilt by replaying the partition from the start.
        // This is the setting that makes an in-memory engine recoverable at all.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);

        JsonDeserializer<T> valueDeserializer = new JsonDeserializer<>(type, objectMapper, false);
        valueDeserializer.setUseTypeHeaders(false);

        ConsumerFactory<String, T> consumerFactory = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(),
                new ErrorHandlingDeserializer<>(valueDeserializer));

        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setBatchListener(true);
        factory.setConcurrency(engineProperties.concurrency());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderAcceptedEvent>
    acceptedOrderListenerFactory(DefaultErrorHandler errorHandler) {
        return batchFactory(OrderAcceptedEvent.class, errorHandler);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderCancelRequestedEvent>
    cancelRequestListenerFactory(DefaultErrorHandler errorHandler) {
        return batchFactory(OrderCancelRequestedEvent.class, errorHandler);
    }

    /**
     * Blocking retry, then the dead-letter topic.
     *
     * <p>Non-blocking retry is not an option here at all, not merely a worse one: a retried
     * order republished to a retry topic would be matched after orders that arrived later,
     * and price-time priority would be violated. The book's correctness depends on strict
     * offset-order processing, so head-of-line blocking is the only acceptable behaviour.
     */
    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> deadLetterKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> {
                    log.error("Routing {}-{}@{} to the dead-letter topic: {}",
                            record.topic(), record.partition(), record.offset(),
                            exception.getMessage());
                    return new org.apache.kafka.common.TopicPartition(
                            record.topic() + Topics.DLT_SUFFIX, record.partition());
                });

        ExponentialBackOff backOff = new ExponentialBackOff(100L, 2.0);
        backOff.setMaxElapsedTime(30_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class);
        return handler;
    }
}
