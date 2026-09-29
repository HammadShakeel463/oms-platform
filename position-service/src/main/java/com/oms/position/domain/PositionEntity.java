package com.oms.position.domain;

import com.oms.common.domain.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * A holding in one symbol for one account, and the average-cost P&amp;L arithmetic that maintains
 * it.
 *
 * <h2>Why the state is (netQuantity, openCost) and not (netQuantity, avgCost)</h2>
 *
 * <p>The textbook representation stores an average cost. It is wrong in a way that only shows up
 * after a few thousand fills: every increase recomputes the average from the previously
 * <em>rounded</em> average, so the error compounds. Store the exact total cost of the open quantity
 * instead and the average becomes a derived, display-only quantity:
 *
 * <ul>
 *   <li>increasing a position is {@code openCost += price × quantity} - exact, no division;</li>
 *   <li>reducing needs exactly one division, and only one;</li>
 *   <li>a <b>full close removes exactly the cost that went in</b>, so a flattened position has
 *       {@code openCost = 0} to the last paisa and the realised figure balances.</li>
 * </ul>
 *
 * <p>That last property is enforced by a CHECK constraint in the schema
 * ({@code net_quantity <> 0 OR open_cost = 0}), so a rounding bug cannot hide - it fails the write
 * rather than leaving a fraction of a rupee of phantom cost that somebody finds during a
 * reconciliation six months later.
 *
 * <p>Average-cost accounting inherently requires a division on every reduction, so a rounding
 * policy is unavoidable. The policy is HALF_UP at 8 decimal places, and the residual is absorbed
 * into realised P&amp;L rather than left on the position. A FIFO or specific-lot basis would avoid
 * the division entirely by tracking individual lots - materially more state, and a different
 * accounting method rather than a more accurate version of this one.
 *
 * <h2>The case that gets implemented wrong</h2>
 *
 * <p><b>Crossing through zero.</b> Selling 150 against a long of 100 is two things at once: a close
 * of 100 that realises P&amp;L, and the opening of a new short of 50 whose cost basis is this
 * fill's price - not the old average. Implementations that treat a fill as "same side or opposite
 * side" and nothing more either realise P&amp;L on the whole 150 (inventing profit on quantity that
 * was never held) or carry the long's average cost onto the new short (mispricing it for ever).
 * {@link #applyFill} handles it explicitly, and {@code PositionEntityTest} pins it.
 */
@Entity
@Table(name = "position")
public class PositionEntity {

    /** Scale for the derived average and for internal division. */
    public static final int COST_SCALE = 8;
    /** Scale for stored money amounts. */
    public static final int MONEY_SCALE = 4;

    @EmbeddedId
    private Key id;

    /** Signed: positive long, negative short, zero flat. */
    @Column(name = "net_quantity", nullable = false)
    private long netQuantity;

    /** Exact total cost of the open quantity. Always non-negative. */
    @Column(name = "open_cost", nullable = false, precision = 24, scale = COST_SCALE)
    private BigDecimal openCost = BigDecimal.ZERO;

    @Column(name = "realised_pnl", nullable = false, precision = 24, scale = MONEY_SCALE)
    private BigDecimal realisedPnl = BigDecimal.ZERO;

    @Column(name = "bought_quantity", nullable = false)
    private long boughtQuantity;

    @Column(name = "sold_quantity", nullable = false)
    private long soldQuantity;

    @Column(name = "fill_count", nullable = false)
    private int fillCount;

    /**
     * Optimistic lock.
     *
     * <p>Contention is expected to be nil: all trades for a symbol arrive on one Kafka partition
     * and are processed by one thread, so two threads never touch the same (account, symbol) row.
     * The lock is here for the case where that reasoning stops holding - a rebalance mid-flight, or
     * an operator replaying a DLT while the live consumer runs - and in that case a lost update
     * would silently corrupt a P&amp;L figure. Cheap insurance against the failure mode that is
     * hardest to detect.
     */
    @Version
    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected PositionEntity() {
    }

    public PositionEntity(String accountId, String symbol) {
        this.id = new Key(accountId, symbol);
        this.netQuantity = 0L;
        this.openCost = BigDecimal.ZERO;
        this.realisedPnl = BigDecimal.ZERO;
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

    // =================================================================================
    //  The arithmetic
    // =================================================================================

    /**
     * Applies one fill and returns what it realised.
     *
     * @param side  the side of the fill from THIS account's point of view
     * @param price execution price
     * @param quantity always positive; {@code side} carries the direction
     * @return realised P&amp;L for this fill; zero when the fill opened or increased the position,
     *         negative when it closed at a loss
     */
    public BigDecimal applyFill(Side side, BigDecimal price, long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive, was " + quantity);
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("fill price must be positive, was " + price);
        }

        long signed = side.sign() * quantity;
        BigDecimal notional = price.multiply(BigDecimal.valueOf(quantity));

        fillCount++;
        if (side == Side.BUY) {
            boughtQuantity += quantity;
        } else {
            soldQuantity += quantity;
        }

        BigDecimal realised;

        if (netQuantity == 0) {
            // Opening from flat.
            netQuantity = signed;
            openCost = notional;
            realised = BigDecimal.ZERO;

        } else if (isSameDirection(netQuantity, signed)) {
            // Increasing. No division, so no rounding: this is the whole reason openCost is
            // stored rather than an average.
            netQuantity += signed;
            openCost = openCost.add(notional);
            realised = BigDecimal.ZERO;

        } else {
            // Reducing, closing, or crossing through zero.
            long openAbs = Math.abs(netQuantity);
            long closing = Math.min(quantity, openAbs);
            long remaining = quantity - closing;
            boolean wasLong = netQuantity > 0;

            // Cost attributable to the closed quantity. When the whole position is closed this
            // is openCost exactly - no division, no residual - which is what makes a flattened
            // position balance to zero.
            BigDecimal costRemoved = closing == openAbs
                    ? openCost
                    : openCost.multiply(BigDecimal.valueOf(closing))
                            .divide(BigDecimal.valueOf(openAbs), COST_SCALE, RoundingMode.HALF_UP);

            BigDecimal proceeds = price.multiply(BigDecimal.valueOf(closing));

            // Closing a long: sold for proceeds, cost was costRemoved.
            // Closing a short: bought back for proceeds, received costRemoved when sold.
            realised = wasLong ? proceeds.subtract(costRemoved) : costRemoved.subtract(proceeds);

            netQuantity += signed;

            if (remaining > 0) {
                // Crossed through zero. The new position on the other side is opened at THIS
                // fill's price - carrying the old average across would misprice it permanently.
                openCost = price.multiply(BigDecimal.valueOf(remaining));
            } else if (netQuantity == 0) {
                openCost = BigDecimal.ZERO;
            } else {
                openCost = openCost.subtract(costRemoved);
            }
        }

        BigDecimal rounded = realised.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        realisedPnl = realisedPnl.add(rounded);
        return rounded;
    }

    private static boolean isSameDirection(long net, long signed) {
        return (net > 0 && signed > 0) || (net < 0 && signed < 0);
    }

    /** Average cost per unit of the open quantity. Zero when flat. Derived, never stored. */
    public BigDecimal averageCost() {
        if (netQuantity == 0) {
            return BigDecimal.ZERO.setScale(COST_SCALE);
        }
        return openCost.divide(BigDecimal.valueOf(Math.abs(netQuantity)),
                COST_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Mark-to-market P&amp;L on the open quantity.
     *
     * <p>{@code (mark − avgCost) × netQuantity}, and the signed quantity does the work for both
     * directions: a long with the mark above cost gives positive × positive, and a short with the
     * mark below cost gives negative × negative. No branch on side, which is one fewer place to
     * get a sign wrong.
     *
     * @param markPrice latest mark, or null if the symbol has never ticked
     * @return null when there is no mark - explicitly "unknown", never a fabricated zero
     */
    public BigDecimal unrealisedPnl(BigDecimal markPrice) {
        if (markPrice == null || netQuantity == 0) {
            return netQuantity == 0 ? BigDecimal.ZERO.setScale(MONEY_SCALE) : null;
        }
        return markPrice.subtract(averageCost())
                .multiply(BigDecimal.valueOf(netQuantity))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Notional value of the open position at the mark. */
    public BigDecimal marketValue(BigDecimal markPrice) {
        if (markPrice == null) {
            return null;
        }
        return markPrice.multiply(BigDecimal.valueOf(netQuantity))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public boolean isFlat() {
        return netQuantity == 0;
    }

    public boolean isLong() {
        return netQuantity > 0;
    }

    public boolean isShort() {
        return netQuantity < 0;
    }

    // --- accessors --------------------------------------------------------------------

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

    public BigDecimal getOpenCost() {
        return openCost;
    }

    public BigDecimal getRealisedPnl() {
        return realisedPnl;
    }

    public long getBoughtQuantity() {
        return boughtQuantity;
    }

    public long getSoldQuantity() {
        return soldQuantity;
    }

    public int getFillCount() {
        return fillCount;
    }

    public int getRevision() {
        return revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PositionEntity other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return PositionEntity.class.hashCode();
    }

    @Override
    public String toString() {
        return "Position[" + getAccountId() + " " + getSymbol()
                + " net=" + netQuantity + " avg=" + averageCost().toPlainString()
                + " realised=" + realisedPnl.toPlainString() + "]";
    }
}
