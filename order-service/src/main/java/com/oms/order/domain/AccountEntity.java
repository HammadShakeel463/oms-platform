package com.oms.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Risk limits for an account. Read on every order; written rarely. */
@Entity
@Table(name = "account")
public class AccountEntity {

    @Id
    @Column(name = "account_id", length = 32)
    private String accountId;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "max_order_notional", nullable = false, precision = 18, scale = 4)
    private BigDecimal maxOrderNotional;

    @Column(name = "max_position_qty", nullable = false)
    private long maxPositionQty;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected AccountEntity() {
    }

    public AccountEntity(String accountId, String displayName,
                         BigDecimal maxOrderNotional, long maxPositionQty) {
        this.accountId = accountId;
        this.displayName = displayName;
        this.maxOrderNotional = maxOrderNotional;
        this.maxPositionQty = maxPositionQty;
        this.active = true;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public BigDecimal getMaxOrderNotional() {
        return maxOrderNotional;
    }

    public long getMaxPositionQty() {
        return maxPositionQty;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
