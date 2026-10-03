package com.oms.matching.metrics;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.matching.book.NewOrder;
import com.oms.matching.book.RecordingListener;
import com.oms.matching.config.EngineProperties;
import com.oms.matching.engine.MatchingEngine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's gauges.
 *
 * <p>Two things here are design decisions rather than plumbing, and both have a test:
 *
 * <ol>
 *   <li><b>Every gauge reads the published snapshot, never the live book.</b> A Prometheus scrape
 *       is just another reader (ADR 0005). Walking the book's {@code TreeMap} from the scrape
 *       thread would be a data race on the hot path, sampled every fifteen seconds, in
 *       production - the hardest possible place to attribute a bug to.
 *   <li><b>A one-sided book reports {@code NaN}, not 0.</b> Prometheus treats NaN as absent, so
 *       the graph shows a gap rather than a plausible wrong number that somebody later writes an
 *       alert against.
 * </ol>
 */
class EngineMetricsTest {

    private MatchingEngine engine;
    private SimpleMeterRegistry registry;
    private EngineMetrics metrics;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine(new EngineProperties(10, 1_024, 0, 1));
        registry = new SimpleMeterRegistry();
        metrics = new EngineMetrics(engine, registry);
    }

    @Test
    @DisplayName("book count is live, and starts at zero before any symbol is touched")
    void bookCountTracksSlots() {
        assertThat(gauge("oms.engine.books")).isZero();

        rest("HBL", Side.BUY, 1_724_000L, 500);
        rest("ENGRO", Side.SELL, 2_800_000L, 300);

        assertThat(gauge("oms.engine.books")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("nothing is reported for a book whose snapshot has not been published yet")
    void unpublishedBookReportsNothing() {
        rest("HBL", Side.BUY, 1_724_000L, 500);
        metrics.refresh();

        assertThat(levels("HBL", "bid"))
                .as("the gauge reads the published snapshot; an unpublished book is empty to a reader")
                .isZero();
    }

    @Test
    @DisplayName("levels and quantity come from the published snapshot, per symbol and per side")
    void levelsAndQuantityReflectThePublishedSnapshot() {
        rest("HBL", Side.BUY, 1_724_000L, 500);
        rest("HBL", Side.BUY, 1_723_000L, 300);
        rest("HBL", Side.SELL, 1_725_000L, 400);
        engine.publishSnapshot("HBL");

        metrics.refresh();

        assertThat(levels("HBL", "bid")).isEqualTo(2.0);
        assertThat(levels("HBL", "ask")).isEqualTo(1.0);
        assertThat(quantity("HBL", "bid")).isEqualTo(800.0);
        assertThat(quantity("HBL", "ask")).isEqualTo(400.0);
    }

    @Test
    @DisplayName("the spread is the published best ask minus the published best bid")
    void spreadIsReported() {
        rest("HBL", Side.BUY, 1_724_000L, 500);
        rest("HBL", Side.SELL, 1_725_000L, 400);
        engine.publishSnapshot("HBL");

        metrics.refresh();

        assertThat(spread("HBL")).isEqualTo(1_000.0);
    }

    @Test
    @DisplayName("a one-sided book reports NaN, so Prometheus shows a gap and not a wrong number")
    void oneSidedBookReportsNaN() {
        rest("HBL", Side.BUY, 1_724_000L, 500);
        engine.publishSnapshot("HBL");

        metrics.refresh();

        assertThat(spread("HBL"))
                .as("0 would mean a locked market, which is a different and alarming thing")
                .isNaN();
    }

    @Test
    @DisplayName("an empty book reports NaN for the spread too")
    void emptyBookReportsNaN() {
        engine.slot("HBL");
        engine.publishSnapshot("HBL");

        metrics.refresh();

        assertThat(spread("HBL")).isNaN();
        assertThat(levels("HBL", "bid")).isZero();
    }

    @Test
    @DisplayName("refresh is idempotent: repeated scrapes do not accumulate duplicate series")
    void refreshDoesNotAccumulateSeries() {
        rest("HBL", Side.BUY, 1_724_000L, 500);
        rest("HBL", Side.SELL, 1_725_000L, 400);
        engine.publishSnapshot("HBL");

        metrics.refresh();
        metrics.refresh();
        metrics.refresh();

        long bidSeries = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("oms.engine.book.levels"))
                .filter(m -> "bid".equals(m.getId().getTag("side")))
                .count();
        assertThat(bidSeries)
                .as("MultiGauge.register(rows, true) overwrites; without the overwrite flag "
                        + "every scrape would add a series and the registry would grow forever")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a symbol whose book empties stops reporting instead of reporting its last value")
    void vanishedSymbolStopsReporting() {
        rest("HBL", Side.BUY, 1_724_000L, 500);
        rest("ENGRO", Side.BUY, 2_800_000L, 300);
        engine.publishSnapshot("HBL");
        engine.publishSnapshot("ENGRO");
        metrics.refresh();
        assertThat(levels("ENGRO", "bid")).isEqualTo(1.0);

        engine.resetSymbol("ENGRO");
        engine.publishSnapshot("ENGRO");
        metrics.refresh();

        assertThat(levels("ENGRO", "bid"))
                .as("a stale gauge value is worse than no value: it reports depth that is gone")
                .isZero();
        assertThat(levels("HBL", "bid")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("every symbol with a book is reported, not just the first")
    void everySymbolIsReported() {
        rest("HBL", Side.BUY, 1_724_000L, 500);
        rest("ENGRO", Side.BUY, 2_800_000L, 300);
        rest("LUCK", Side.SELL, 8_100_000L, 100);
        engine.symbols().forEach(engine::publishSnapshot);

        metrics.refresh();

        assertThat(levels("HBL", "bid")).isEqualTo(1.0);
        assertThat(levels("ENGRO", "bid")).isEqualTo(1.0);
        assertThat(levels("LUCK", "ask")).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------------------------

    private void rest(String symbol, Side side, long priceTicks, long quantity) {
        engine.submit(symbol, new NewOrder(UUID.randomUUID(), "ACC-1", side,
                        OrderType.LIMIT, TimeInForce.DAY, priceTicks, quantity),
                new RecordingListener());
    }

    private double gauge(String name) {
        Gauge g = registry.find(name).gauge();
        return g == null ? Double.NaN : g.value();
    }

    private double levels(String symbol, String side) {
        return seriesValue("oms.engine.book.levels", symbol, side);
    }

    private double quantity(String symbol, String side) {
        return seriesValue("oms.engine.book.quantity", symbol, side);
    }

    private double spread(String symbol) {
        Gauge g = registry.find("oms.engine.book.spread.ticks").tag("symbol", symbol).gauge();
        return g == null ? 0.0 : g.value();
    }

    /** 0 when the series is absent, which is what "stopped reporting" looks like to a scrape. */
    private double seriesValue(String name, String symbol, String side) {
        Gauge g = registry.find(name).tag("symbol", symbol).tag("side", side).gauge();
        return g == null ? 0.0 : g.value();
    }
}
