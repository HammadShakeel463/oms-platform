package com.oms.order.api.dto;

import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.order.domain.OrderEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a client sees.
 *
 * <p>A separate type from {@link OrderEntity} on purpose, and the mapping is one explicit
 * static method rather than a mapping framework. Returning the entity directly would leak
 * the schema into the API (every column rename becomes a breaking API change), expose
 * fields no client should see, and - the one that actually bites in production - serialise
 * lazy associations outside the transaction, producing either a
 * {@code LazyInitializationException} or an accidental cascade of queries during JSON
 * writing.
 *
 * <p>{@code leavesQuantity} is computed rather than stored: it is derived state, and
 * sending it saves every client from re-deriving it slightly differently.
 */
public record OrderResponse(
        UUID orderId,
        String clientOrderId,
        String accountId,
        String symbol,
        Side side,
        OrderType orderType,
        TimeInForce timeInForce,
        BigDecimal limitPrice,
        long quantity,
        long filledQuantity,
        long leavesQuantity,
        BigDecimal avgPrice,
        OrderStatus status,
        String rejectReason,
        Instant createdAt,
        Instant updatedAt
) {

    public static OrderResponse from(OrderEntity order) {
        return new OrderResponse(
                order.getOrderId(),
                order.getClientOrderId(),
                order.getAccountId(),
                order.getSymbol(),
                order.getSide(),
                order.getOrderType(),
                order.getTimeInForce(),
                order.getLimitPrice(),
                order.getQuantity(),
                order.getFilledQuantity(),
                order.leavesQuantity(),
                order.getAvgPrice(),
                order.getStatus(),
                order.getRejectReason(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
