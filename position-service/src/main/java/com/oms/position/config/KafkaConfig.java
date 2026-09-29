package com.oms.position.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.event.MarketTickEvent;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.event.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Two consumers with deliberately different policies.
 *
 * <p>The trade consumer is the money path: ordered, record-at-a-time, offset committed after the
 * transaction, exponential backoff. The tick consumer is a cache refresher: batched, one quick
 * retry, then let it go - blocking a partition for thirty seconds over a value the next tick
 * overwrites would cost more than the value is worth.
 *
 * <p>Applying one retry policy to both would be wrong in one direction or the other: either the
 * money path gives up too early, or the cache path holds up a partition over stale data.
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

    private <T> DefaultKafkaConsumerFactory<String, T> consumerFactory(
            Class<T> type, String groupId, String offsetReset, int maxPollRecords) {

        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);

        JsonDeserializer<T> valueDeserializer = new JsonDeserializer<>(type, objectMapper, false);
        valueDeserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new ErrorHandlingDeserializer<>(valueDeserializer));
    }

    /**
     * Trades. {@code auto-offset-reset=earliest}, because every position here is DERIVED from this
     * topic: a fresh deployment with an empty database rebuilds every position by replaying it from
     * the beginning. That is the recovery story, and it works only because the ledger primary key
     * makes replay idempotent.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TradeExecutedEvent> tradeListenerFactory(
            PositionProperties properties,
            @Qualifier("tradeErrorHandler") DefaultErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, TradeExecutedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory(TradeExecutedEvent.class,
                properties.tradeConsumerGroup(), "earliest", 50));
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * Ticks. {@code auto-offset-reset=latest}, because a mark is a live value. Replaying a whole
     * session of ticks to arrive at the current mark would be waste, and marking positions at
     * hours-old prices on the way through would be actively wrong.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, MarketTickEvent> tickListenerFactory(
            PositionProperties properties,
            @Qualifier("tickErrorHandler") DefaultErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, MarketTickEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory(MarketTickEvent.class,
                properties.tickConsumerGroup(), "latest", 500));
        factory.setCommonErrorHandler(errorHandler);
        factory.setBatchListener(true);
        factory.setConcurrency(2);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        return factory;
    }

    @Bean
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        DefaultKafkaProducerFactory<Object, Object> pf = new DefaultKafkaProducerFactory<>(props);
        DelegatingByTypeSerializer serializer = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                String.class, new StringSerializer()));
        pf.setKeySerializer(serializer);
        pf.setValueSerializer(serializer);
        return new KafkaTemplate<>(pf);
    }

    private static DeadLetterPublishingRecoverer recoverer(KafkaTemplate<Object, Object> template) {
        return new DeadLetterPublishingRecoverer(template, (record, exception) -> {
            log.error("Routing {}-{}@{} to the dead-letter topic: {}",
                    record.topic(), record.partition(), record.offset(), exception.getMessage());
            return new org.apache.kafka.common.TopicPartition(
                    record.topic() + Topics.DLT_SUFFIX, record.partition());
        });
    }

    @Bean("tradeErrorHandler")
    public DefaultErrorHandler tradeErrorHandler(KafkaTemplate<Object, Object> deadLetterKafkaTemplate) {
        ExponentialBackOff backOff = new ExponentialBackOff(100L, 2.0);
        backOff.setMaxElapsedTime(30_000L);

        DefaultErrorHandler handler =
                new DefaultErrorHandler(recoverer(deadLetterKafkaTemplate), backOff);
        // Retryable: an optimistic lock collision, a dropped connection. Not retryable: a malformed
        // payload or an impossible fill, which fails identically every time while blocking the
        // partition for the whole backoff budget.
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class);
        return handler;
    }

    @Bean("tickErrorHandler")
    public DefaultErrorHandler tickErrorHandler(KafkaTemplate<Object, Object> deadLetterKafkaTemplate) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer(deadLetterKafkaTemplate), new FixedBackOff(100L, 1));
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class);
        return handler;
    }
}
