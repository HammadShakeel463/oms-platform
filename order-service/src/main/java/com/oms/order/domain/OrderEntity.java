package com.oms.order.domain;

import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.error.IllegalStateTransitionException;
import com.oms.common.money.Ticks;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The current state of an order.
 *
 * <p>This is a mutable class with a protected no-arg constructor, which is the opposite of
 * everything in {@code oms-common}. That is deliberate and worth being able to explain: a
 * {@code record} models a <em>value</em> (two orders with the same fields are the same
 * order-shaped value), while an entity models an <em>identity</em> (this order, with this
 * id, whose fields change over time). JPA needs to construct the object before it has the
 * data, and needs to detect field changes to emit UPDATE statements, so an entity cannot be
 * a record. Using a record here would not merely be unidiomatic - it would not work.
 *
 * <p>Equality is by {@code orderId} only, never by the generated field-wise equality a
 * record would give. Two loads of the same row are the same order even if one is stale.
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "client_order_id", nullable = false, updatable = false, length = 64)
    private String clientOrderId;

    @Column(name = "account_id", nullable = false, updatable = false, length = 32)
    private String accountId;

    @Column(name = "symbol", nullable = false, updatable = false, length = 16)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, updatable = false, length = 4)
    private Side side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, updatable = false, length = 8)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_in_force", nullable = false, updatable = false, length = 4)
    private TimeInForce timeInForce;

    /** Null for MARKET orders; the database CHECK constraint enforces the pairing. */
    @Column(name = "limit_price", precision = 18, scale = 4, updatable = false)
    private BigDecimal limitPrice;

    @Column(name = "quantity", nullable = false, updatable = false)
    private long quantity;

    @Column(name = "filled_quantity", nullable = false)
    private long filledQuantity;

    @Column(name = "avg_price", precision = 18, scale = 4)
    private BigDecimal avgPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "reject_reason", length = 256)
    private String rejectReason;

    /**
     * Optimistic lock. Hibernate adds {@code WHERE revision = ?} to every UPDATE and
     * throws {@link org.springframework.dao.OptimisticLockingFailureException} if no row
     * matched.
     *
     * <p>The alternative, {@code SELECT ... FOR UPDATE}, holds a row lock for the length of
     * the transaction. On the order path that transaction includes a Kafka-bound outbox
     * insert, so pessimistic locking would serialise every concurrent fill for the same
     * order behind a lock held across application work. Optimistic locking costs nothing
     * when there is no contention - which is the normal case, since fills for one order
     * arrive on one partition - and turns the rare genuine race into a retry.
     */
    @Version
    @Column(name = "revision", nullable = false)
    private int revision;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Protected rather than public so application code cannot use it. */
    protected OrderEntity() {
    }

    private OrderEntity(UUID orderId, String clientOrderId, String accountId, String symbol,
                        Side side, OrderType orderType, TimeInForce timeInForce,
                        BigDecimal limitPrice, long quantity) {
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.orderType = orderType;
        this.timeInForce = timeInForce;
        this.limitPrice = limitPrice;
        this.quantity = quantity;
        this.filledQuantity = 0L;
        this.status = OrderStatus.NEW;
    }

    /**
     * The only way to create an order. A factory rather than a public constructor because
     * an order is always born in {@link OrderStatus#NEW} with zero fills, and that is an
     * invariant rather than a default the caller may override.
     */
    public static OrderEntity newOrder(String clientOrderId, String accountId, String symbol,
                                       Side side, OrderType orderType, TimeInForce timeInForce,
                                       BigDecimal limitPrice, long quantity) {
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(symbol, "symbol");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (orderType.requiresLimitPrice() == (limitPrice == null)) {
            throw new IllegalArgumentException(
                    orderType == OrderType.LIMIT
                            ? "LIMIT order requires a limit price"
                            : "MARKET order must not carry a limit price");
        }
        return new OrderEntity(UUID.randomUUID(), clientOrderId, accountId, symbol,
                side, orderType, timeInForce, limitPrice, quantity);
    }

    // --- behaviour -------------------------------------------------------------------

    /**
     * Moves to {@code target}, or throws. Every transition in the service goes through
     * here, so the transition table in {@code OrderStatus} is the only authority on what
     * a legal history looks like.
     */
    public void transitionTo(OrderStatus target, String reason) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(status, target);
        }
        this.status = target;
        if (reason != null) {
            this.rejectReason = reason.length() > 256 ? reason.substring(0, 256) : reason;
        }
    }

    /**
     * Applies an execution: increases filled quantity and rolls the volume-weighted
     * average price forward.
     *
     * <p>The caller decides the resulting status, because "is this order now FILLED" is a
     * question about leaves quantity that the state machine owns.
     */
    public void applyFill(BigDecimal price, long fillQuantity) {
        if (fillQuantity <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive");
        }
        if (fillQuantity > leavesQuantity()) {
            throw new IllegalArgumentException(
                    "overfill: " + fillQuantity + " exceeds leaves " + leavesQuantity()
                            + " on order " + orderId);
        }
        BigDecimal previousNotional = avgPrice == null
                ? BigDecimal.ZERO
                : avgPrice.multiply(BigDecimal.valueOf(filledQuantity));
        BigDecimal fillNotional = price.multiply(BigDecimal.valueOf(fillQuantity));

        this.filledQuantity += fillQuantity;
        this.avgPrice = previousNotional.add(fillNotional)
                .divide(BigDecimal.valueOf(filledQuantity), Ticks.SCALE, RoundingMode.HALF_UP);
    }

    public long leavesQuantity() {
        return quantity - filledQuantity;
    }

    public boolean isFullyFilled() {
        return filledQuantity == quantity;
    }

    public long limitPriceTicks() {
        return Ticks.fromDecimal(limitPrice);
    }

    public long avgPriceTicks() {
        return Ticks.fromDecimal(avgPrice);
    }

    /** Signed quantity of this order, for exposure arithmetic. */
    public long signedQuantity() {
        return side.sign() * quantity;
    }

    // --- accessors --------------------------------------------------------------------

    public UUID getOrderId() {
        return orderId;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public Side getSide() {
        return side;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public TimeInForce getTimeInForce() {
        return timeInForce;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public long getQuantity() {
        return quantity;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public BigDecimal getAvgPrice() {
        return avgPrice;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public int getRevision() {
        return revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // --- identity ---------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        // instanceof pattern matching (Java 16+): binds and casts in one step. Note the
        // deliberate use of instanceof rather than getClass() - Hibernate hands back proxy
        // subclasses for lazy associations, and a getClass() comparison breaks on them.
        return o instanceof OrderEntity other
                && orderId != null
                && orderId.equals(other.orderId);
    }

    @Override
    public int hashCode() {
        // Constant, not Objects.hash(orderId). The id is assigned before persist here so
        // it never changes, but a constant hash is the safe habit for entities: if the id
        // were database-generated, an entity added to a HashSet before flush would change
        // its hash afterwards and become unfindable in that set.
        return OrderEntity.class.hashCode();
    }

    @Override
    public String toString() {
        return "Order[" + orderId + " " + side + " " + quantity + " " + symbol
                + (limitPrice != null ? " @" + limitPrice.toPlainString() : " MKT")
                + " " + status + " filled=" + filledQuantity + "]";
    }
}
