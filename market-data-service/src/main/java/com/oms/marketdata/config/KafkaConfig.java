package com.oms.marketdata.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.event.TradeExecutedEvent;
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
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;

    public KafkaConfig(KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * The tick producer, and the only place in the platform where durability is deliberately
     * traded away for throughput.
     *
     * <p><b>{@code acks=1}, not {@code acks=all}.</b> Every other producer here waits for the
     * in-sync replicas, because losing an order or a fill is unacceptable. A tick is different: it
     * is superseded 100 milliseconds later, the topic has one-hour retention, and nothing
     * reconstructs state from it. Paying replication latency on the highest-volume topic in the
     * platform to protect data that is obsolete before it could be replayed is the wrong trade.
     *
     * <p>Idempotence stays on - it costs nothing and removes duplicate ticks from producer retries.
     * {@code linger.ms=5} batches a whole generation pass into few requests; at one tick per symbol
     * per 100ms that turns ten requests into one.
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
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

    /**
     * Consumes executed trades, to print the last traded price into the quote.
     *
     * <p>Its own consumer group, separate from order-service and position-service, so the three
     * read the same topic at their own pace with their own offsets. That independence is the whole
     * point of a log over a queue.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TradeExecutedEvent>
    tradePrintListenerFactory(DefaultErrorHandler errorHandler) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // latest, not earliest: a restarting instance wants the current market, not a replay of
        // this morning's prints. The last-trade field is a live value, not a ledger.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        JsonDeserializer<TradeExecutedEvent> valueDeserializer =
                new JsonDeserializer<>(TradeExecutedEvent.class, objectMapper, false);
        valueDeserializer.setUseTypeHeaders(false);

        ConsumerFactory<String, TradeExecutedEvent> consumerFactory =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                        new ErrorHandlingDeserializer<>(valueDeserializer));

        ConcurrentKafkaListenerContainerFactory<String, TradeExecutedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(2);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * A fixed, short backoff with few attempts - not the exponential policy the order path uses.
     *
     * <p>The effect of this consumer is to update a display value that the next tick will overwrite
     * anyway. Retrying a failed print for thirty seconds while the partition is blocked would cost
     * more than the print is worth. Two quick attempts, then the dead-letter topic, and move on.
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

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(200L, 2));
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class);
        return handler;
    }
}
