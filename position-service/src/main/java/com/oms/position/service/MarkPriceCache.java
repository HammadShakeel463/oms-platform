package com.oms.position.service;

import com.oms.common.event.MarketTickEvent;
import com.oms.common.money.Ticks;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Latest mark price per symbol, in memory.
 *
 * <h2>Why this is not in the database, and not in Redis</h2>
 *
 * <p>A mark price is pure derived state with a lifetime of about 100 milliseconds. Persisting it
 * would mean a write per symbol per tick - thousands of writes a second to store a value that is
 * obsolete before the transaction commits - and buy nothing, because after a restart the cache
 * refills from the tick stream within one generation interval. Redis would be the same trade with a
 * network hop attached.
 *
 * <p>The consequence is stated honestly rather than hidden: <b>for the first moment after a restart
 * this service can report a realised P&amp;L and no unrealised P&amp;L.</b> That is why
 * {@code unrealisedPnl} returns null rather than zero for an unmarked symbol - "unknown" and
 * "nothing" are different answers, and a risk report that silently shows zero exposure because it
 * lost its marks is worse than one that says it does not know.
 *
 * <h2>Concurrency</h2>
 *
 * <p>A {@link ConcurrentHashMap} of immutable {@link Mark} records. Writers are the tick consumer
 * threads, readers are HTTP threads. {@code put} of an immutable value into a ConcurrentHashMap is
 * safe publication, and a reader either sees the old mark or the new one - never a half-updated one.
 * The same pattern as the engine's snapshot, at a smaller scale.
 *
 * <p>Last-value-wins is correct here for the same reason it is correct in the streaming mailbox: a
 * mark supersedes its predecessor. Out-of-order tick delivery would briefly mark a position at a
 * slightly older price, which is indistinguishable from the tick having arrived 100ms later - so
 * the sequence number is kept and older sequences are ignored, which costs one comparison and
 * removes the question entirely.
 */
@Component
public class MarkPriceCache {

    /** An immutable mark. */
    public record Mark(String symbol, BigDecimal price, long sequence, Instant asOf) {
    }

    private final Map<String, Mark> marks = new ConcurrentHashMap<>();

    public MarkPriceCache(MeterRegistry meterRegistry) {
        Gauge.builder("oms.position.marked.symbols", marks, Map::size)
                .description("Symbols with a known mark price")
                .register(meterRegistry);
    }

    /**
     * Records a tick as the new mark, unless an equal or newer one is already held.
     *
     * <p>The mark is the mid, not the last trade: a mid derived from a live two-sided quote is the
     * price a position could plausibly be closed at, whereas the last trade may be stale or may have
     * been a single print at an outlier price. When there is no two-sided market the mid is absent
     * and the last trade is used instead, which is the standard fallback.
     */
    public void update(MarketTickEvent tick) {
        long midTicks = tick.midPriceTicks();
        long markTicks = midTicks != Ticks.NO_PRICE ? midTicks : tick.lastPriceTicks();
        if (markTicks == Ticks.NO_PRICE) {
            return;
        }

        BigDecimal price = Ticks.toDecimal(markTicks);
        marks.merge(tick.symbol(),
                new Mark(tick.symbol(), price, tick.sequence(), tick.occurredAt()),
                // Keep whichever is newer. Guards against out-of-order delivery across the
                // partitions of a multi-threaded consumer.
                (existing, incoming) -> incoming.sequence() >= existing.sequence() ? incoming : existing);
    }

    /** @return the mark, or empty when the symbol has never ticked on this instance */
    public Optional<BigDecimal> priceOf(String symbol) {
        Mark mark = marks.get(symbol);
        return mark == null ? Optional.empty() : Optional.of(mark.price());
    }

    public Optional<Mark> markOf(String symbol) {
        return Optional.ofNullable(marks.get(symbol));
    }

    public int size() {
        return marks.size();
    }

    public void clear() {
        marks.clear();
    }
}
