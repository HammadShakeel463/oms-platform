package com.oms.position.domain;

import com.oms.common.domain.Side;
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
 * One immutable row per (trade, account): the workings behind a position.
 *
 * <p>The composite key {@code (tradeId, accountId)} is doing two jobs at once. It is the audit
 * identity, and it is the idempotency guarantee - a replayed trade collides here and the handler
 * treats the collision as "already applied". Idempotency enforced by a constraint beats
 * idempotency enforced by a SELECT-then-INSERT, which is a race dressed up as a check.
 *
 * <p>Per-account rather than per-trade because one trade has two sides and each side belongs to a
 * different account. Recording it once per account means an account's ledger is a complete history
 * of its own activity with no joins and no filtering.
 *
 * <p>No setters: the database rejects UPDATE and DELETE via a trigger, and the object model agrees.
 */
@Entity
@Table(name = "trade_ledger")
public class TradeLedgerEntry {

    @EmbeddedId
    private Key id;

    @Column(name = "symbol", nullable = false, length = 16, updatable = false)
    private String symbol;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 4, updatable = false)
    private Side side;

    @Column(name = "price", nullable = false, precision = 18, scale = 4, updatable = false)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false, updatable = false)
    private long quantity;

    @Column(name = "realised_delta", nullable = false, precision = 24, scale = 4, updatable = false)
    private BigDecimal realisedDelta;

    @Column(name = "net_after", nullable = false, updatable = false)
    private long netAfter;

    @Column(name = "avg_cost_after", nullable = false, precision = 24, scale = 8, updatable = false)
    private BigDecimal avgCostAfter;

    @Column(name = "engine_seq", nullable = false, updatable = false)
    private long engineSeq;

    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt;

    @Column(name = "recorded_at", insertable = false, updatable = false)
    private Instant recordedAt;

    protected TradeLedgerEntry() {
    }

    public TradeLedgerEntry(UUID tradeId, String accountId, String symbol, UUID orderId,
                            Side side, BigDecimal price, long quantity,
                            BigDecimal realisedDelta, long netAfter, BigDecimal avgCostAfter,
                            long engineSeq, Instant executedAt) {
        this.id = new Key(tradeId, accountId);
        this.symbol = symbol;
        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.realisedDelta = realisedDelta;
        this.netAfter = netAfter;
        this.avgCostAfter = avgCostAfter;
        this.engineSeq = engineSeq;
        this.executedAt = executedAt;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "trade_id", nullable = false)
        private UUID tradeId;

        @Column(name = "account_id", nullable = false, length = 32)
        private String accountId;

        protected Key() {
        }

        public Key(UUID tradeId, String accountId) {
            this.tradeId = tradeId;
            this.accountId = accountId;
        }

        public UUID getTradeId() {
            return tradeId;
        }

        public String getAccountId() {
            return accountId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(tradeId, k.tradeId)
                    && Objects.equals(accountId, k.accountId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tradeId, accountId);
        }
    }

    public Key getId() {
        return id;
    }

    public UUID getTradeId() {
        return id.getTradeId();
    }

    public String getAccountId() {
        return id.getAccountId();
    }

    public String getSymbol() {
        return symbol;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Side getSide() {
        return side;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getRealisedDelta() {
        return realisedDelta;
    }

    public long getNetAfter() {
        return netAfter;
    }

    public BigDecimal getAvgCostAfter() {
        return avgCostAfter;
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
