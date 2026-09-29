package com.oms.position.messaging;

import com.oms.common.event.DomainEvent;
import com.oms.common.event.MarketTickEvent;
import com.oms.common.event.OrderAcceptedEvent;
import com.oms.common.event.OrderCancelConfirmedEvent;
import com.oms.common.event.OrderCancelRequestedEvent;
import com.oms.common.event.OrderLifecycleEvent;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.event.Topics;
import com.oms.position.service.MarkPriceCache;
import com.oms.position.service.PositionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The two streams position-service consumes, and the one place the sealed event hierarchy earns
 * its keep.
 *
 * <h2>Two consumers, two groups, on purpose</h2>
 *
 * <p>Trades arrive at tens per second and must be applied in order, transactionally, exactly once
 * in effect. Ticks arrive at thousands per second, are conflatable, and update an in-memory value.
 * Sharing one listener container would let a tick backlog delay a trade - which delays a position
 * update, which delays a risk decision.
 *
 * <p>So they are separate containers in separate consumer groups with separate offsets and separate
 * thread pools, and <b>the tick consumer is the one that is allowed to fall behind</b>. That
 * asymmetry is the design; it is not an accident of configuration.
 *
 * <h2>The exhaustive switch</h2>
 *
 * <p>Both listeners funnel into {@link #apply(DomainEvent)}, which switches over the sealed
 * {@code DomainEvent} hierarchy with no {@code default} branch. That is the Java 17 feature doing
 * real work rather than being demonstrated:
 *
 * <ul>
 *   <li>The compiler verifies every case is handled. Adding a seventh event type to
 *       {@code oms-common} makes <b>this class fail to compile</b> until somebody decides what
 *       position-service should do with it.</li>
 *   <li>The events this service deliberately ignores are <b>written down as ignoring them</b>. A
 *       {@code default -> {}} branch would silently absorb a new event type, and the failure mode of
 *       a new event that nobody noticed is a position that is quietly wrong.</li>
 * </ul>
 *
 * <p>For a C++ reader: this is {@code std::variant} with a compiler-checked visitor, minus the
 * visitor boilerplate. The pre-17 Java alternative - an {@code instanceof} chain ending in
 * {@code default -> throw new IllegalStateException()} - moves an error the compiler could have
 * caught into production.
 */
@Component
public class PositionEventListener {

    private static final Logger log = LoggerFactory.getLogger(PositionEventListener.class);

    private final PositionService positionService;
    private final MarkPriceCache markPriceCache;
    private final MeterRegistry meterRegistry;

    public PositionEventListener(PositionService positionService,
                                 MarkPriceCache markPriceCache,
                                 MeterRegistry meterRegistry) {
        this.positionService = positionService;
        this.markPriceCache = markPriceCache;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Trades. Record-at-a-time, with the offset committed only after the transaction commits, so a
     * crash mid-handler replays the record - which the ledger's primary key makes safe.
     */
    @KafkaListener(
            topics = Topics.TRADES_EXECUTED,
            groupId = "${oms.position.trade-consumer-group:position-service-trades}",
            containerFactory = "tradeListenerFactory")
    public void onTrade(TradeExecutedEvent event) {
        if (event == null) {
            return;
        }
        try (var ignored = MDC.putCloseable("tradeId", String.valueOf(event.tradeId()))) {
            apply(event);
        }
    }

    /**
     * Ticks, in batches.
     *
     * <p>Batched because the effect of a tick is a map write: applying 500 of them one at a time
     * costs 500 poll-loop iterations to do work that is essentially free. Last-value-wins means the
     * batch can be applied in order with no coordination.
     */
    @KafkaListener(
            topics = Topics.MARKET_DATA_TICKS,
            groupId = "${oms.position.tick-consumer-group:position-service-marks}",
            containerFactory = "tickListenerFactory",
            batch = "true")
    public void onTicks(List<MarketTickEvent> events) {
        for (MarketTickEvent event : events) {
            if (event != null) {
                apply(event);
            }
        }
    }

    /**
     * The single dispatch point. No {@code default} branch, by design.
     */
    private void apply(DomainEvent event) {
        switch (event) {
            case TradeExecutedEvent trade -> positionService.applyTrade(trade);

            case MarketTickEvent tick -> markPriceCache.update(tick);

            // --- events this service is not subscribed to -------------------------------
            // Listed explicitly rather than swallowed by a default branch. If a future
            // refactor points a topic at this listener, the compiler has already made
            // somebody think about each of these.
            case OrderAcceptedEvent ignored -> ignored(event);
            case OrderCancelRequestedEvent ignored -> ignored(event);
            case OrderCancelConfirmedEvent ignored -> ignored(event);

            // The lifecycle stream is a candidate for a future "open orders" projection here.
            // Until then it is an explicit no-op rather than an oversight.
            case OrderLifecycleEvent ignored -> ignored(event);
        }
    }

    private void ignored(DomainEvent event) {
        meterRegistry.counter("oms.position.events.ignored",
                "type", event.getClass().getSimpleName()).increment();
        log.warn("position-service received {} which it does not handle - check topic wiring",
                event.getClass().getSimpleName());
    }
}
