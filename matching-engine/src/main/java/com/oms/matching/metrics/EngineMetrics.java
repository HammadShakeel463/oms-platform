package com.oms.matching.metrics;

import com.oms.common.money.Ticks;
import com.oms.matching.book.BookSnapshot;
import com.oms.matching.engine.MatchingEngine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Exports book state to Prometheus.
 *
 * <p>Every gauge here reads the <em>published immutable snapshot</em>, never the live book. A
 * metrics scrape is just another reader, and it must not be able to interfere with matching -
 * which is precisely the property the snapshot design buys. The naive version, walking the
 * book's TreeMap from the scrape thread, would be a data race on the hot path, sampled every
 * fifteen seconds, in production, where it would be nearly impossible to attribute.
 */
@Component
public class EngineMetrics {

    private final MatchingEngine engine;
    private final MultiGauge bookLevels;
    private final MultiGauge spread;
    private final MultiGauge liveQuantity;

    public EngineMetrics(MatchingEngine engine, MeterRegistry registry) {
        this.engine = engine;
        this.bookLevels = MultiGauge.builder("oms.engine.book.levels")
                .description("Price levels on a side, from the last published snapshot")
                .register(registry);
        this.spread = MultiGauge.builder("oms.engine.book.spread.ticks")
                .description("Best ask minus best bid, in ticks")
                .register(registry);
        this.liveQuantity = MultiGauge.builder("oms.engine.book.quantity")
                .description("Resting quantity on a side, from the last published snapshot")
                .register(registry);

        Gauge.builder("oms.engine.books", engine, MatchingEngine::bookCount)
                .description("Symbols with a live book on this instance")
                .register(registry);
    }

    /**
     * Rows are rebuilt rather than registered once per symbol.
     *
     * <p>Symbols come and go with Kafka partition assignments. A gauge registered per symbol
     * would hold a strong reference to a book this instance no longer owns - a leak that also
     * reports stale depth for a symbol another instance is now matching.
     */
    @Scheduled(fixedDelayString = "${oms.engine.metrics-refresh-interval:5000}")
    public void refresh() {
        List<MultiGauge.Row<?>> levelRows = new ArrayList<>();
        List<MultiGauge.Row<?>> spreadRows = new ArrayList<>();
        List<MultiGauge.Row<?>> quantityRows = new ArrayList<>();

        for (String symbol : engine.symbols()) {
            BookSnapshot snapshot = engine.snapshot(symbol);

            levelRows.add(MultiGauge.Row.of(
                    Tags.of("symbol", symbol, "side", "bid"), snapshot.bids().size()));
            levelRows.add(MultiGauge.Row.of(
                    Tags.of("symbol", symbol, "side", "ask"), snapshot.asks().size()));

            quantityRows.add(MultiGauge.Row.of(
                    Tags.of("symbol", symbol, "side", "bid"), sumQuantity(snapshot.bids())));
            quantityRows.add(MultiGauge.Row.of(
                    Tags.of("symbol", symbol, "side", "ask"), sumQuantity(snapshot.asks())));

            long spreadTicks = snapshot.spreadTicks();
            // NaN rather than 0 or -1 for "no two-sided market": Prometheus treats NaN as
            // absent, so a one-sided book leaves a gap in the graph instead of a plausible
            // wrong number that someone will later alert on.
            spreadRows.add(MultiGauge.Row.of(Tags.of("symbol", symbol),
                    spreadTicks == Ticks.NO_PRICE ? Double.NaN : spreadTicks));
        }

        bookLevels.register(levelRows, true);
        spread.register(spreadRows, true);
        liveQuantity.register(quantityRows, true);
    }

    private static double sumQuantity(List<BookSnapshot.Level> levels) {
        long total = 0;
        for (BookSnapshot.Level level : levels) {
            total += level.quantity();
        }
        return total;
    }
}
