package com.oms.position.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "oms.position")
public record PositionProperties(
        String tradeConsumerGroup,
        String tickConsumerGroup
) {

    public PositionProperties {
        if (tradeConsumerGroup == null || tradeConsumerGroup.isBlank()) {
            tradeConsumerGroup = "position-service-trades";
        }
        if (tickConsumerGroup == null || tickConsumerGroup.isBlank()) {
            tickConsumerGroup = "position-service-marks";
        }
    }
}
