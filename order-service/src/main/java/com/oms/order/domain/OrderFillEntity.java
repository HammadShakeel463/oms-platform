package com.oms.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One execution against one order.
 *
 * <p>The composite primary key {@code (tradeId, orderId)} is the idempotency guarantee for
 * the trade consumer: Kafka replays a {@code TradeExecutedEvent}, the second insert
 * violates the key, and the handler treats that as "already applied". Idempotency enforced
 * by a constraint beats idempotency enforced by a SELECT-then-INSERT, which is a race
 * rather than a guarantee.
 */
@Entity
@Table(name = "order_fill")
public class OrderFillEntity {

    @EmbeddedId
    private Key id;

    @Column(name = "symbol", nullable = false, length = 16)
    private String symbol;

    @Column(name = "price", nullable = false, precision = 18, scale = 4)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "liquidity", nullable = false, length = 8)
    private Liquidity liquidity;

    @Column(name = "engine_seq", nullable = false)
    private long engineSeq;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "recorded_at", insertable = false, updatable = false)
    private Instant recordedAt;

    protected OrderFillEntity() {
    }

    public OrderFillEntity(UUID tradeId, UUID orderId, String symbol, BigDecimal price,
                           long quantity, Liquidity liquidity, long engineSeq,
                           Instant executedAt) {
        this.id = new Key(tradeId, orderId);
        this.symbol = symbol;
        this.price = price;
        this.quantity = quantity;
        this.liquidity = liquidity;
        this.engineSeq = engineSeq;
        this.executedAt = executedAt;
    }

    /**
     * Composite key. {@code @Embeddable} keys must implement {@code Serializable} and have
     * value semantics - which is exactly what a record gives, but records cannot be used
     * here because Hibernate needs a no-arg constructor and field access.
     */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "trade_id", nullable = false)
        private UUID tradeId;

        @Column(name = "order_id", nullable = false)
        private UUID orderId;

        protected Key() {
        }

        public Key(UUID tradeId, UUID orderId) {
            this.tradeId = tradeId;
            this.orderId = orderId;
        }

        public UUID getTradeId() {
            return tradeId;
        }

        public UUID getOrderId() {
            return orderId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(tradeId, k.tradeId)
                    && Objects.equals(orderId, k.orderId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tradeId, orderId);
        }
    }

    public Key getId() {
        return id;
    }

    public UUID getTradeId() {
        return id.getTradeId();
    }

    public UUID getOrderId() {
        return id.getOrderId();
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public Liquidity getLiquidity() {
        return liquidity;
    }

    public long getEngineSeq() {
        return engineSeq;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
