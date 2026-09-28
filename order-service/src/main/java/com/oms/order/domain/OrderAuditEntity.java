package com.oms.order.domain;

import com.oms.common.domain.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable row per state transition. The database rejects UPDATE and DELETE on this
 * table via a trigger, so there are no setters here either - the object graph and the
 * schema agree that history does not change.
 */
@Entity
@Table(name = "order_audit")
public class OrderAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    /** Per-order monotonic counter. Timestamps are not an ordering key; this is. */
    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20, updatable = false)
    private OrderStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20, updatable = false)
    private OrderStatus newStatus;

    @Column(name = "reason", length = 256, updatable = false)
    private String reason;

    @Column(name = "filled_quantity", nullable = false, updatable = false)
    private long filledQuantity;

    @Column(name = "leaves_quantity", nullable = false, updatable = false)
    private long leavesQuantity;

    @Column(name = "avg_price", precision = 18, scale = 4, updatable = false)
    private BigDecimal avgPrice;

    /** Who caused this: a user id, or the name of the consumer that applied an event. */
    @Column(name = "actor", nullable = false, length = 64, updatable = false)
    private String actor;

    @Column(name = "trace_id", length = 64, updatable = false)
    private String traceId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected OrderAuditEntity() {
    }

    public OrderAuditEntity(UUID orderId, int seq, OrderStatus previousStatus,
                            OrderStatus newStatus, String reason, long filledQuantity,
                            long leavesQuantity, BigDecimal avgPrice, String actor,
                            String traceId, Instant occurredAt) {
        this.orderId = orderId;
        this.seq = seq;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.reason = reason;
        this.filledQuantity = filledQuantity;
        this.leavesQuantity = leavesQuantity;
        this.avgPrice = avgPrice;
        this.actor = actor;
        this.traceId = traceId;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public int getSeq() {
        return seq;
    }

    public OrderStatus getPreviousStatus() {
        return previousStatus;
    }

    public OrderStatus getNewStatus() {
        return newStatus;
    }

    public String getReason() {
        return reason;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public long getLeavesQuantity() {
        return leavesQuantity;
    }

    public BigDecimal getAvgPrice() {
        return avgPrice;
    }

    public String getActor() {
        return actor;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
