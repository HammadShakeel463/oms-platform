package com.oms.marketdata.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.event.MarketTickEvent;
import com.oms.common.event.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes ticks to Kafka.
 *
 * <p>Fire and forget: the send future is deliberately not awaited. This is the one publisher in the
 * platform that does not wait for the broker, and the reasoning is the same as for acks=1 - a tick
 * is superseded in 100 milliseconds, and blocking the generator thread on a broker round trip would
 * mean one slow broker stalls the feed for every subscriber and for the Redis cache too.
 *
 * <p>The failure callback is what keeps that honest: a dropped tick is counted, so "the feed is
 * lossy" is a number on a dashboard rather than a silence.
 */
@Component
public class TickPublisher {

    private static final Logger log = LoggerFactory.getLogger(TickPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Counter published;
    private final Counter failed;

    public TickPublisher(KafkaTemplate<String, String> kafkaTemplate,
                         ObjectMapper objectMapper,
                         MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.published = meterRegistry.counter("oms.marketdata.ticks.published");
        this.failed = meterRegistry.counter("oms.marketdata.ticks.failed");
    }

    public void publish(MarketTickEvent tick) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(tick);
        } catch (JsonProcessingException e) {
            failed.increment();
            log.error("Could not serialise tick for {}", tick.symbol(), e);
            return;
        }

        kafkaTemplate.send(new ProducerRecord<>(Topics.MARKET_DATA_TICKS, tick.partitionKey(), payload))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        failed.increment();
                        log.debug("Tick publish failed for {}: {}", tick.symbol(), error.toString());
                    } else {
                        published.increment();
                    }
                });
    }
}
