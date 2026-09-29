package com.oms.marketdata.simulator;

import com.oms.common.event.MarketTickEvent;
import com.oms.common.money.Ticks;
import com.oms.marketdata.config.MarketDataProperties;
import com.oms.marketdata.domain.InstrumentEntity;
import com.oms.marketdata.messaging.TickPublisher;
import com.oms.marketdata.quote.QuoteCache;
import com.oms.marketdata.reference.InstrumentService;
import com.oms.marketdata.stream.TickBroadcaster;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the simulated feed: one pass per interval over every active instrument.
 *
 * <p>Single-threaded by construction - one scheduler thread owns every {@link SymbolState}, which
 * is the same single-writer discipline as the matching engine (ADR 0005) and for the same reason.
 * Nothing here takes a lock.
 *
 * <p>Each pass, per symbol: advance the walk, publish to Kafka, write through to Redis, and fan out
 * to streaming subscribers. The order is deliberate - Kafka first, because it is the durable
 * record; the cache and the stream are derived views and a failure in either must not lose the tick.
 */
@Component
public class TickGenerator {

    private static final Logger log = LoggerFactory.getLogger(TickGenerator.class);

    private final InstrumentService instrumentService;
    private final TickPublisher publisher;
    private final QuoteCache quoteCache;
    private final TickBroadcaster broadcaster;
    private final MarketDataProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /**
     * Symbol to simulated state. Concurrent because the trade listener looks up state from a Kafka
     * thread to hand over a print, while this class creates and iterates entries.
     */
    private final Map<String, SymbolState> states = new ConcurrentHashMap<>();

    private final Timer passTimer;

    public TickGenerator(InstrumentService instrumentService,
                         TickPublisher publisher,
                         QuoteCache quoteCache,
                         TickBroadcaster broadcaster,
                         MarketDataProperties properties,
                         MeterRegistry meterRegistry,
                         Clock clock) {
        this.instrumentService = instrumentService;
        this.publisher = publisher;
        this.quoteCache = quoteCache;
        this.broadcaster = broadcaster;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.passTimer = Timer.builder("oms.marketdata.generation.pass")
                .description("Time to generate and fan out one tick for every active symbol")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        Gauge.builder("oms.marketdata.symbols", states, Map::size)
                .description("Symbols with simulated state on this instance")
                .register(meterRegistry);
    }

    /**
     * One pass over the universe.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}, for the same reason as the outbox publisher:
     * fixed rate schedules from the previous <em>start</em>, so a pass that overruns causes runs to
     * overlap and pile up. Fixed delay measures from the previous completion and self-throttles.
     *
     * <p>The cost is that the cadence drifts under load rather than staying exactly on the
     * interval. For a simulator that is the right trade - an approximately 100ms feed that cannot
     * collapse beats an exactly 100ms feed that can.
     */
    @Scheduled(fixedDelayString = "${oms.marketdata.tick-interval:100}")
    public void generateTicks() {
        if (!properties.simulatorEnabled()) {
            return;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        int published = 0;
        try {
            Collection<InstrumentEntity> instruments = instrumentService.activeInstruments();
            Instant now = clock.instant();

            for (InstrumentEntity instrument : instruments) {
                SymbolState state = states.computeIfAbsent(instrument.getSymbol(),
                        symbol -> newState(instrument));

                MarketTickEvent tick = state.nextTick(now);

                publisher.publish(tick);
                quoteCache.put(state.currentSnapshot());
                broadcaster.publish(tick);
                published++;
            }

            // Symbols that have been halted or delisted since the last pass keep their state
            // object otherwise, and would show in the gauge for ever.
            if (states.size() > instruments.size()) {
                var active = instruments.stream().map(InstrumentEntity::getSymbol).toList();
                states.keySet().removeIf(symbol -> !active.contains(symbol));
            }
        } catch (Exception e) {
            // A scheduled method that throws is silently not rescheduled in some configurations
            // and, worse, logs nothing useful. Catch, count, carry on: a feed that stops because
            // one database call failed is a worse outcome than a gap in the feed.
            meterRegistry.counter("oms.marketdata.generation.errors").increment();
            log.error("Tick generation pass failed", e);
        } finally {
            sample.stop(passTimer);
            if (log.isTraceEnabled()) {
                log.trace("Generated {} ticks for {} subscribers",
                        published, broadcaster.subscriberCount());
            }
        }
    }

    private SymbolState newState(InstrumentEntity instrument) {
        long tickSize = Ticks.fromDecimal(instrument.getTickSize());
        log.info("Starting simulated feed for {} at reference {}",
                instrument.getSymbol(), instrument.getReferencePrice().toPlainString());
        return new SymbolState(
                instrument.getSymbol(),
                Ticks.fromDecimal(instrument.getReferencePrice()),
                tickSize,
                tickSize * properties.spreadTicks(),
                properties.maxStepTicks(),
                properties.baseSize());
    }

    /** The live snapshot for a symbol, bypassing Redis. Used by the snapshot endpoint first. */
    public Optional<com.oms.common.marketdata.QuoteSnapshot> liveSnapshot(String symbol) {
        SymbolState state = states.get(symbol);
        return state == null ? Optional.empty() : Optional.of(state.currentSnapshot());
    }

    /** Hands a trade print to the owning symbol's state. Called from a Kafka consumer thread. */
    public void recordTradePrint(String symbol, long priceTicks, long quantity) {
        SymbolState state = states.get(symbol);
        if (state != null) {
            state.offerTradePrint(priceTicks, quantity);
        }
    }

    public int symbolCount() {
        return states.size();
    }
}
