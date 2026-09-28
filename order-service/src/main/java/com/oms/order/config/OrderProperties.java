package com.oms.order.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Typed configuration, bound from {@code oms.order.*}.
 *
 * <p>{@code @ConfigurationProperties} on a record is the modern alternative to scattering
 * {@code @Value("${...}")} across the codebase. Three concrete advantages worth naming:
 * the values are validated at startup rather than discovered wrong at first use; a typo in
 * a property name is visible in one class instead of hidden in an injection point; and the
 * type is a {@code Duration} or a {@code BigDecimal} rather than a String you parse
 * yourself.
 *
 * <p>{@code @Validated} makes the Jakarta constraints below fail the application context -
 * the process does not start with a nonsensical slippage factor. Failing at startup rather
 * than at the first order is the whole point.
 */
@Validated
@ConfigurationProperties(prefix = "oms.order")
public record OrderProperties(

        /** Widening applied to the reference price when risk-checking a MARKET order. */
        @NotNull @DecimalMin("0.0") BigDecimal marketOrderSlippage,

        /** Rows the outbox publisher claims per poll. */
        @Min(1) int outboxBatchSize,

        /** How long published outbox rows are kept for forensics before cleanup. */
        @NotNull Duration outboxRetention
) {

    public OrderProperties {
        if (marketOrderSlippage == null) {
            marketOrderSlippage = new BigDecimal("0.05");
        }
        if (outboxBatchSize <= 0) {
            outboxBatchSize = 200;
        }
        if (outboxRetention == null) {
            outboxRetention = Duration.ofDays(3);
        }
    }
}
