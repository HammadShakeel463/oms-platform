package com.oms.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.money.Ticks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the wire contract. If Jackson cannot round-trip a record, every consumer in the
 * platform breaks at runtime rather than at compile time - so this is checked in the module
 * that owns the contract, not in each service.
 */
class EventContractTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    @DisplayName("OrderAcceptedEvent round-trips through JSON")
    void orderAcceptedRoundTrip() throws Exception {
        var event = new OrderAcceptedEvent(
                UUID.randomUUID().toString(), Instant.parse("2026-09-28T09:15:00Z"),
                OrderAcceptedEvent.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(), "cl-1", "ACC-1", "HBL",
                Side.BUY, OrderType.LIMIT, TimeInForce.DAY,
                Ticks.fromDecimal(new BigDecimal("172.4500")), 500);

        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"occurredAt\":\"2026-09-28T09:15:00Z\"");

        assertThat(mapper.readValue(json, OrderAcceptedEvent.class)).isEqualTo(event);
    }

    @Test
    @DisplayName("TradeExecutedEvent round-trips and exposes both sides")
    void tradeRoundTrip() throws Exception {
        var buyOrder = UUID.randomUUID();
        var sellOrder = UUID.randomUUID();
        var event = new TradeExecutedEvent(
                UUID.randomUUID().toString(), Instant.now(),
                TradeExecutedEvent.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(), "HBL", 1_724_500L, 100, Side.BUY,
                buyOrder, "ACC-1", sellOrder, "ACC-2", 42L);

        assertThat(mapper.readValue(mapper.writeValueAsString(event), TradeExecutedEvent.class))
                .isEqualTo(event);
        assertThat(event.orderIdFor(Side.SELL)).isEqualTo(sellOrder);
        assertThat(event.accountIdFor(Side.BUY)).isEqualTo("ACC-1");
    }

    @Test
    @DisplayName("every event declares its partition key, and it is never blank")
    void partitionKeysArePresent() {
        DomainEvent order = new OrderAcceptedEvent(
                "e1", Instant.now(), 1, UUID.randomUUID(), "c", "ACC-1", "HBL",
                Side.SELL, OrderType.MARKET, TimeInForce.IOC, Ticks.NO_PRICE, 10);
        DomainEvent tick = new MarketTickEvent("e2", Instant.now(), 1, "HBL",
                1_724_000L, 100, 1_725_000L, 200, 1_724_500L, 50, 7L);

        assertThat(order.partitionKey()).isEqualTo("HBL");
        assertThat(tick.partitionKey()).isEqualTo("HBL");
    }

    @Test
    @DisplayName("an invalid event cannot be constructed")
    void invariantsAreEnforcedInTheConstructor() {
        assertThatThrownBy(() -> new OrderAcceptedEvent(
                "e1", Instant.now(), 1, UUID.randomUUID(), "c", "ACC-1", "HBL",
                Side.BUY, OrderType.LIMIT, TimeInForce.DAY, 1_000L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity must be positive");
    }

    @Test
    @DisplayName("tick helpers degrade to NO_PRICE on a one-sided book")
    void oneSidedBook() {
        var tick = new MarketTickEvent("e", Instant.now(), 1, "HBL",
                Ticks.NO_PRICE, 0, 1_725_000L, 200, 1_724_500L, 50, 1L);

        assertThat(tick.midPriceTicks()).isEqualTo(Ticks.NO_PRICE);
        assertThat(tick.spreadTicks()).isEqualTo(Ticks.NO_PRICE);
    }

    @Test
    @DisplayName("topic names carry their major version, and DLT naming is derived")
    void topicNaming() {
        assertThat(Topics.ORDERS_ACCEPTED).isEqualTo("oms.orders.accepted.v1");
        assertThat(Topics.deadLetterFor(Topics.TRADES_EXECUTED))
                .isEqualTo("oms.trades.executed.v1.dlt");
    }
}
