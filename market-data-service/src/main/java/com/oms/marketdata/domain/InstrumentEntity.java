package com.oms.marketdata.domain;

import com.oms.common.money.Ticks;
import com.oms.common.reference.InstrumentStatus;
import com.oms.common.reference.InstrumentView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A tradeable instrument.
 *
 * <p>Note the {@link #toView()} method: this entity never leaves the service. It is mapped to
 * {@link InstrumentView} - the shared DTO from {@code oms-common} - at the boundary. That is
 * the rule from ADR 0001 in one method: services share contracts, never persistence models.
 * The conversion also crosses the representation boundary from ADR 0002, turning
 * {@code NUMERIC} prices into fixed-point ticks for the consumers that do arithmetic on them.
 */
@Entity
@Table(name = "instrument")
public class InstrumentEntity {

    @Id
    @Column(name = "symbol", nullable = false, updatable = false, length = 16)
    private String symbol;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "isin", nullable = false, length = 12)
    private String isin;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "lot_size", nullable = false)
    private long lotSize;

    @Column(name = "tick_size", nullable = false, precision = 18, scale = 4)
    private BigDecimal tickSize;

    @Column(name = "price_band_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal priceBandPercent;

    @Column(name = "reference_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal referencePrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private InstrumentStatus status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected InstrumentEntity() {
    }

    public InstrumentEntity(String symbol, String name, String isin, String currency,
                            long lotSize, BigDecimal tickSize, BigDecimal priceBandPercent,
                            BigDecimal referencePrice, InstrumentStatus status) {
        this.symbol = symbol;
        this.name = name;
        this.isin = isin;
        this.currency = currency;
        this.lotSize = lotSize;
        this.tickSize = tickSize;
        this.priceBandPercent = priceBandPercent;
        this.referencePrice = referencePrice;
        this.status = status;
    }

    /** The shared contract representation, with prices converted to fixed-point ticks. */
    public InstrumentView toView() {
        return new InstrumentView(
                symbol, name, isin, currency, lotSize,
                Ticks.fromDecimal(tickSize),
                priceBandPercent,
                Ticks.fromDecimal(referencePrice),
                status);
    }

    public boolean isTradeable() {
        return status == InstrumentStatus.ACTIVE;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }

    public String getIsin() {
        return isin;
    }

    public String getCurrency() {
        return currency;
    }

    public long getLotSize() {
        return lotSize;
    }

    public BigDecimal getTickSize() {
        return tickSize;
    }

    public BigDecimal getPriceBandPercent() {
        return priceBandPercent;
    }

    public BigDecimal getReferencePrice() {
        return referencePrice;
    }

    public InstrumentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InstrumentEntity other
                && symbol != null
                && symbol.equals(other.symbol);
    }

    @Override
    public int hashCode() {
        return InstrumentEntity.class.hashCode();
    }

    @Override
    public String toString() {
        return "Instrument[" + symbol + " " + status + " ref=" + referencePrice + "]";
    }
}
