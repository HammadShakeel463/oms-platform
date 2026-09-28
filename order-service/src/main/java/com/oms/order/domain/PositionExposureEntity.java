package com.oms.order.domain;

import com.oms.common.domain.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * order-service's own read model of an account's exposure in one symbol.
 *
 * <p>Built from this service's own fills and its own working orders - never fetched from
 * position-service. A pre-trade risk check runs on the order path, and a synchronous
 * dependency there means another service being slow makes order entry slow, and that
 * service being down stops trading altogether.
 *
 * <p>{@code workingBuyQty}/{@code workingSellQty} are residual quantities on live orders.
 * Counting only filled quantity would be wrong: an account whose working orders would
 * breach its limit is already committed to breaching it.
 */
@Entity
@Table(name = "position_exposure")
public class PositionExposureEntity {

    @EmbeddedId
    private Key id;

    /** Signed: positive is long, negative is short. */
    @Column(name = "net_quantity", nullable = false)
    private long netQuantity;

    @Column(name = "working_buy_qty", nullable = false)
    private long workingBuyQty;

    @Column(name = "working_sell_qty", nullable = false)
    private long workingSellQty;

    @Version
    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected PositionExposureEntity() {
    }

    public PositionExposureEntity(String accountId, String symbol) {
        this.id = new Key(accountId, symbol);
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "account_id", nullable = false, length = 32)
        private String accountId;

        @Column(name = "symbol", nullable = false, length = 16)
        private String symbol;

        protected Key() {
        }

        public Key(String accountId, String symbol) {
            this.accountId = accountId;
            this.symbol = symbol;
        }

        public String getAccountId() {
            return accountId;
        }

        public String getSymbol() {
            return symbol;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(accountId, k.accountId)
                    && Objects.equals(symbol, k.symbol);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountId, symbol);
        }
    }

    // --- exposure arithmetic -----------------------------------------------------------

    /** An order went live: its full quantity is now working. */
    public void addWorking(Side side, long quantity) {
        if (side == Side.BUY) {
            workingBuyQty += quantity;
        } else {
            workingSellQty += quantity;
        }
    }

    /** Working quantity left the book, by fill or by cancel. */
    public void removeWorking(Side side, long quantity) {
        if (side == Side.BUY) {
            workingBuyQty = Math.max(0, workingBuyQty - quantity);
        } else {
            workingSellQty = Math.max(0, workingSellQty - quantity);
        }
    }

    /** A fill moved quantity from working into the net position. */
    public void applyFill(Side side, long quantity) {
        removeWorking(side, quantity);
        netQuantity += side.sign() * quantity;
    }

    /**
     * Worst-case position if every working order on {@code side} fills, plus
     * {@code additionalQuantity} more. This is the quantity a max-position check compares
     * against the limit: the question is not "where am I now" but "where could this order
     * put me".
     */
    public long worstCaseAfter(Side side, long additionalQuantity) {
        return side == Side.BUY
                ? netQuantity + workingBuyQty + additionalQuantity
                : netQuantity - workingSellQty - additionalQuantity;
    }

    public Key getId() {
        return id;
    }

    public String getAccountId() {
        return id.getAccountId();
    }

    public String getSymbol() {
        return id.getSymbol();
    }

    public long getNetQuantity() {
        return netQuantity;
    }

    public long getWorkingBuyQty() {
        return workingBuyQty;
    }

    public long getWorkingSellQty() {
        return workingSellQty;
    }

    public int getRevision() {
        return revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
