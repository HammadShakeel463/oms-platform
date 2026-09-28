package com.oms.order.api.dto;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Order entry payload.
 *
 * <p>Bean Validation annotations are the declarative layer: shape, range and presence.
 * They run before the controller method body, and a violation becomes a 400 with a
 * per-field breakdown - none of that is hand-written. What they cannot express is anything
 * requiring the database or reference data, which is why lot size, tick size, the price
 * band and account limits are risk checks rather than annotations. The dividing line is
 * simple: if the rule can be evaluated by looking only at this object, it belongs here.
 *
 * <p>Cross-field rules - a LIMIT order must carry a price, a MARKET order must not - are
 * the awkward case for annotations. They are enforced in {@code OrderEntity.newOrder} and
 * by a CHECK constraint in the schema, where they read as a single readable condition
 * instead of a custom class-level constraint annotation.
 *
 * @param clientOrderId caller-supplied idempotency key, unique per account
 * @param limitPrice    required for LIMIT, must be absent for MARKET
 */
public record PlaceOrderRequest(

        @NotBlank(message = "clientOrderId is required")
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9._:-]+",
                message = "clientOrderId may contain only letters, digits and . _ : -")
        String clientOrderId,

        @NotBlank(message = "symbol is required")
        @Size(max = 16)
        @Pattern(regexp = "[A-Z0-9.]+", message = "symbol must be upper case")
        String symbol,

        @NotNull(message = "side is required (BUY or SELL)")
        Side side,

        @NotNull(message = "orderType is required (LIMIT or MARKET)")
        OrderType orderType,

        @NotNull(message = "timeInForce is required (DAY, IOC or FOK)")
        TimeInForce timeInForce,

        // Scale 4 matches the tick scale. A price with more precision is rejected here
        // rather than silently rounded - see ADR 0002.
        @DecimalMin(value = "0.0001", message = "limitPrice must be positive")
        @Digits(integer = 14, fraction = 4,
                message = "limitPrice supports at most 4 decimal places")
        BigDecimal limitPrice,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 100_000_000L, message = "quantity exceeds the maximum single-order size")
        long quantity
) {
}
