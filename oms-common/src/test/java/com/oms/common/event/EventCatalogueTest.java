package com.oms.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.marketdata.QuoteSnapshot;
import com.oms.common.money.Ticks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rest of the event catalogue: the three events {@link EventContractTest} does not cover, the
 * sealed hierarchy itself, and the partition-key rule that the whole ordering design rests on.
 *
 * <p>The partition-key assertions are the ones with teeth. "Orders are keyed by symbol" is what
 * gives the matching engine a single writer per book with no lock, and "lifecycle events are keyed
 * by order id" is what lets them fan out without constraining the engine. If a key silently
 * changed to something else, nothing would fail to compile and nothing would throw - the platform
 * would just stop being correct under concurrency.
 */
class EventCatalogueTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    @DisplayName("OrderCancelRequestedEvent round-trips, and is keyed by symbol so it cannot "
            + "overtake the order it cancels")
    void cancelRequestedRoundTrips() throws Exception {
        UUID orderId = UUID.randomUUID();
        var event = new OrderCancelRequestedEvent(
                UUID.randomUUID().toString(), Instant.parse("2026-09-28T09:20:00Z"),
                OrderCancelRequestedEvent.CURRENT_SCHEMA_VERSION,
                orderId, "HBL", "ACC-1", "trader1");

        assertThat(mapper.readValue(mapper.writeValueAsString(event), OrderCancelRequestedEvent.class))
                .isEqualTo(event);
        assertThat(event.partitionKey())
                .as("keyed by symbol, not order id: same partition as the order means the engine "
                        + "cannot see the cancel before the order")
                .isEqualTo("HBL");
        assertThat(event.requestedBy()).isEqualTo("trader1");
    }

    @Test
    @DisplayName("OrderCancelConfirmedEvent round-trips and reports what was actually cancelled")
    void cancelConfirmedRoundTrips() throws Exception {
        var event = new OrderCancelConfirmedEvent(
                UUID.randomUUID().toString(), Instant.parse("2026-09-28T09:20:01Z"),
                OrderCancelConfirmedEvent.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(), "ENGRO", "ACC-1", CancelReason.IOC_RESIDUAL, 300, 99L);

        assertThat(mapper.readValue(mapper.writeValueAsString(event), OrderCancelConfirmedEvent.class))
                .isEqualTo(event);
        assertThat(event.partitionKey()).isEqualTo("ENGRO");
        assertThat(event.cancelledQuantity())
                .as("the residual, not the original quantity - a partially filled IOC cancels "
                        + "only what is left")
                .isEqualTo(300);
        assertThat(event.reason()).isEqualTo(CancelReason.IOC_RESIDUAL);
    }

    @Test
    @DisplayName("OrderLifecycleEvent round-trips and is keyed by order id, not by symbol")
    void lifecycleRoundTripsAndIsKeyedByOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        var event = new OrderLifecycleEvent(
                UUID.randomUUID().toString(), Instant.parse("2026-09-28T09:21:00Z"),
                OrderLifecycleEvent.CURRENT_SCHEMA_VERSION,
                orderId, "ACC-1", "HBL",
                OrderStatus.ROUTED, OrderStatus.PARTIALLY_FILLED,
                "fill 200 @ 172.45", 200, 300, 1_724_500L, 3);

        assertThat(mapper.readValue(mapper.writeValueAsString(event), OrderLifecycleEvent.class))
                .isEqualTo(event);
        assertThat(event.partitionKey())
                .as("lifecycle is a notification about one order, so per-order ordering is all "
                        + "that is required - keying by symbol would needlessly serialise every "
                        + "order in a symbol behind one another")
                .isEqualTo(orderId.toString());
    }

    @Test
    @DisplayName("a lifecycle event states both ends of the transition, so a consumer never guesses")
    void lifecycleCarriesBothStates() {
        var event = new OrderLifecycleEvent("e", Instant.now(), 1, UUID.randomUUID(),
                "ACC-1", "HBL", OrderStatus.NEW, OrderStatus.REJECTED,
                "MAX_ORDER_VALUE", 0, 0, Ticks.NO_PRICE, 1);

        assertThat(event.previousStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(event.newStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(event.reason()).isEqualTo("MAX_ORDER_VALUE");
        assertThat(event.leavesQuantity()).isZero();
    }

    @Test
    @DisplayName("every event in the sealed hierarchy declares a non-blank partition key and a version")
    void everyEventSatisfiesTheContract() {
        List<DomainEvent> catalogue = List.of(
                new OrderAcceptedEvent("e1", Instant.now(), 1, UUID.randomUUID(), "c1", "ACC-1",
                        "HBL", Side.BUY, OrderType.LIMIT, TimeInForce.DAY, 1_724_500L, 100),
                new OrderCancelRequestedEvent("e2", Instant.now(), 1, UUID.randomUUID(), "HBL",
                        "ACC-1", "trader1"),
                new OrderCancelConfirmedEvent("e3", Instant.now(), 1, UUID.randomUUID(), "HBL",
                        "ACC-1", CancelReason.USER_REQUEST, 100, 1L),
                new OrderLifecycleEvent("e4", Instant.now(), 1, UUID.randomUUID(), "ACC-1", "HBL",
                        OrderStatus.NEW, OrderStatus.VALIDATED, null, 0, 100, Ticks.NO_PRICE, 1),
                new TradeExecutedEvent("e5", Instant.now(), 1, UUID.randomUUID(), "HBL",
                        1_724_500L, 100, Side.BUY, UUID.randomUUID(), "ACC-1",
                        UUID.randomUUID(), "ACC-2", 1L),
                new MarketTickEvent("e6", Instant.now(), 1, "HBL",
                        1_724_000L, 100, 1_725_000L, 200, 1_724_500L, 50, 1L));

        // Six permitted subtypes, six instances. If a seventh event is added to the sealed
        // interface and not added here, this assertion is the reminder.
        assertThat(DomainEvent.class.getPermittedSubclasses()).hasSize(catalogue.size());

        assertThat(catalogue).allSatisfy(event -> {
            assertThat(event.eventId()).isNotBlank();
            assertThat(event.occurredAt()).isNotNull();
            assertThat(event.schemaVersion()).isPositive();
            assertThat(event.partitionKey())
                    .as("a null key makes Kafka round-robin the record, which silently destroys "
                            + "every ordering guarantee the design depends on")
                    .isNotBlank();
        });
    }

    @Test
    @DisplayName("every cancel reason is a distinct, nameable cause - UNKNOWN_ORDER is not a default")
    void cancelReasonsAreExhaustive() {
        assertThat(CancelReason.values())
                .containsExactly(CancelReason.USER_REQUEST, CancelReason.IOC_RESIDUAL,
                        CancelReason.FOK_UNFILLABLE, CancelReason.UNKNOWN_ORDER,
                        CancelReason.SESSION_END);
    }

    @Test
    @DisplayName("QuoteSnapshot round-trips with BigDecimal prices preserved exactly, trailing zeros and all")
    void quoteSnapshotRoundTrips() throws Exception {
        var snapshot = new QuoteSnapshot("HBL",
                new BigDecimal("172.4000"), 1_000,
                new BigDecimal("172.5000"), 800,
                new BigDecimal("172.4500"), 200,
                7L, Instant.parse("2026-09-28T09:15:30Z"));

        String json = mapper.writeValueAsString(snapshot);
        QuoteSnapshot read = mapper.readValue(json, QuoteSnapshot.class);

        assertThat(read).isEqualTo(snapshot);
        assertThat(read.bid())
                .as("the scale is part of the quote: 172.4 and 172.4000 are the same number but "
                        + "not the same price display, and BigDecimal.equals distinguishes them")
                .isEqualTo(new BigDecimal("172.4000"));
        assertThat(json).contains("\"asOf\":\"2026-09-28T09:15:30Z\"");
    }
}
