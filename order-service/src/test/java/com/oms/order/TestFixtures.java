package com.oms.order;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.money.Ticks;
import com.oms.common.reference.InstrumentStatus;
import com.oms.common.reference.InstrumentView;
import com.oms.order.domain.AccountEntity;
import com.oms.order.domain.OrderEntity;
import com.oms.order.domain.PositionExposureEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Builders for test data.
 *
 * <p>Centralised so a test says only what it cares about. A test that reads
 * {@code limitOrder(BUY, 1000, "172.45")} states its intent; the same test with twelve
 * constructor arguments states the constructor signature, and stops compiling every time
 * that signature changes.
 */
public final class TestFixtures {

    public static final String ACCOUNT = "ACC-TRADER-1";
    public static final String SYMBOL = "HBL";

    private TestFixtures() {
    }

    public static AccountEntity account() {
        return account(new BigDecimal("5000000.0000"), 100_000L);
    }

    public static AccountEntity account(BigDecimal maxNotional, long maxPosition) {
        return new AccountEntity(ACCOUNT, "Test Account", maxNotional, maxPosition);
    }

    public static InstrumentView instrument() {
        return instrument("172.4500", new BigDecimal("10.00"), 1L, "0.0100");
    }

    public static InstrumentView instrument(String referencePrice, BigDecimal bandPercent,
                                            long lotSize, String tickSize) {
        return new InstrumentView(SYMBOL, "Habib Bank Limited", "PK0001901014", "PKR",
                lotSize,
                Ticks.fromDecimal(new BigDecimal(tickSize)),
                bandPercent,
                Ticks.fromDecimal(new BigDecimal(referencePrice)),
                InstrumentStatus.ACTIVE);
    }

    public static InstrumentView instrument(InstrumentStatus status) {
        InstrumentView base = instrument();
        return new InstrumentView(base.symbol(), base.name(), base.isin(), base.currency(),
                base.lotSize(), base.tickSizeTicks(), base.priceBandPercent(),
                base.referencePriceTicks(), status);
    }

    public static OrderEntity limitOrder(Side side, long quantity, String price) {
        return OrderEntity.newOrder("cl-" + UUID.randomUUID(), ACCOUNT, SYMBOL, side,
                OrderType.LIMIT, TimeInForce.DAY, new BigDecimal(price), quantity);
    }

    public static OrderEntity marketOrder(Side side, long quantity) {
        return OrderEntity.newOrder("cl-" + UUID.randomUUID(), ACCOUNT, SYMBOL, side,
                OrderType.MARKET, TimeInForce.IOC, null, quantity);
    }

    public static PositionExposureEntity exposure() {
        return new PositionExposureEntity(ACCOUNT, SYMBOL);
    }

    public static TradeExecutedEvent trade(UUID buyOrderId, UUID sellOrderId,
                                           String price, long quantity, Side aggressor) {
        return new TradeExecutedEvent(
                UUID.randomUUID().toString(),
                Instant.parse("2026-09-28T09:15:00Z"),
                TradeExecutedEvent.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(),
                SYMBOL,
                Ticks.fromDecimal(new BigDecimal(price)),
                quantity,
                aggressor,
                buyOrderId, ACCOUNT,
                sellOrderId, "ACC-TRADER-2",
                1L);
    }
}
