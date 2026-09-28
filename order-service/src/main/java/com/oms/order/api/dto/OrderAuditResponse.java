package com.oms.order.api.dto;

import com.oms.common.domain.OrderStatus;
import com.oms.order.domain.OrderAuditEntity;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of the immutable order history. */
public record OrderAuditResponse(
        int seq,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        String reason,
        long filledQuantity,
        long leavesQuantity,
        BigDecimal avgPrice,
        String actor,
        String traceId,
        Instant occurredAt
) {

    public static OrderAuditResponse from(OrderAuditEntity audit) {
        return new OrderAuditResponse(
                audit.getSeq(),
                audit.getPreviousStatus(),
                audit.getNewStatus(),
                audit.getReason(),
                audit.getFilledQuantity(),
                audit.getLeavesQuantity(),
                audit.getAvgPrice(),
                audit.getActor(),
                audit.getTraceId(),
                audit.getOccurredAt());
    }
}
