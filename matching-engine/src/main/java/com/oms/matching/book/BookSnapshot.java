package com.oms.matching.book;

import com.oms.common.money.Ticks;

import java.util.List;

/**
 * An immutable depth-of-book view.
 *
 * <p>This type is the whole concurrency story for readers. The writer builds one of these
 * after processing a batch and publishes it through a volatile reference; readers take the
 * reference and walk a structure that nobody will ever mutate. No lock is taken on either
 * side, and a reader cannot see a torn book.
 *
 * <p>Immutability has to be real, not nominal: the lists are wrapped with
 * {@code List.copyOf} in the factory, because a record holding a mutable list is only
 * shallowly immutable and the writer would otherwise still be holding a reference to the
 * list a reader is iterating.
 */
public record BookSnapshot(
        String symbol,
        long sequence,
        long bestBidTicks,
        long bestAskTicks,
        List<Level> bids,
        List<Level> asks
) {

    /** One aggregated price level. */
    public record Level(long priceTicks, long quantity, int orderCount) {
    }

    public static BookSnapshot of(String symbol, long sequence, long bestBidTicks,
                                 long bestAskTicks, List<Level> bids, List<Level> asks) {
        return new BookSnapshot(symbol, sequence, bestBidTicks, bestAskTicks,
                List.copyOf(bids), List.copyOf(asks));
    }

    public static BookSnapshot empty(String symbol) {
        return new BookSnapshot(symbol, 0L, Ticks.NO_PRICE, Ticks.NO_PRICE,
                List.of(), List.of());
    }

    /** Spread in ticks, or {@code Ticks.NO_PRICE} when either side is empty. */
    public long spreadTicks() {
        if (bestBidTicks == Ticks.NO_PRICE || bestAskTicks == Ticks.NO_PRICE) {
            return Ticks.NO_PRICE;
        }
        return bestAskTicks - bestBidTicks;
    }

    public long midPriceTicks() {
        if (bestBidTicks == Ticks.NO_PRICE || bestAskTicks == Ticks.NO_PRICE) {
            return Ticks.NO_PRICE;
        }
        return (bestBidTicks + bestAskTicks) / 2;
    }

    /**
     * True when the best bid is at or above the best ask.
     *
     * <p>A continuous book can never be crossed - if it were, those two orders would have
     * traded. This is the single most useful invariant to assert in a test, because almost
     * every matching bug shows up as a crossed book.
     */
    public boolean isCrossed() {
        return bestBidTicks != Ticks.NO_PRICE
                && bestAskTicks != Ticks.NO_PRICE
                && bestBidTicks >= bestAskTicks;
    }
}
