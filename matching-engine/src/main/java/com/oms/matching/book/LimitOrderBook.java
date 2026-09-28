package com.oms.matching.book;

import com.oms.common.event.CancelReason;

import java.util.UUID;

/**
 * A single-symbol limit order book with price-time priority.
 *
 * <p><b>Not thread safe, and that is the design, not an omission.</b> Every book is owned by
 * exactly one writer thread. The guarantee comes from Kafka: all orders and cancels for a
 * symbol carry that symbol as the message key, so they land in one partition, and a partition
 * is consumed by one thread. See ADR 0005 and docs/concurrency.md.
 *
 * <p>Readers never call these methods. They read an immutable {@link BookSnapshot} published
 * by the writer through a volatile field, so a depth query cannot block the writer and cannot
 * observe a half-updated book.
 *
 * <p>Two implementations exist on purpose:
 * <ul>
 *   <li>{@link NaiveOrderBook} - the straightforward version, kept so the performance claims
 *       in docs/performance.md are reproducible rather than asserted;</li>
 *   <li>{@link PriceTimeOrderBook} - the tuned version that actually runs.</li>
 * </ul>
 */
public interface LimitOrderBook {

    String symbol();

    /**
     * Matches {@code order} against the book, then rests any residual unless the order is
     * immediate.
     *
     * @return the highest sequence number emitted, or the current sequence if nothing was
     */
    long submit(NewOrder order, MatchListener listener);

    /**
     * Removes a resting order.
     *
     * <p>Reports {@link CancelReason#USER_REQUEST} with the remaining quantity when the order
     * was on the book, or {@link CancelReason#UNKNOWN_ORDER} when it was not - already fully
     * filled, already cancelled, or never seen. The engine is authoritative about its own
     * book, so "I do not have it" is an answer, not an error.
     *
     * @return true if an order was removed
     */
    boolean cancel(UUID orderId, String accountId, MatchListener listener);

    /** Best bid in ticks, or {@code Ticks.NO_PRICE} when the side is empty. */
    long bestBidTicks();

    /** Best ask in ticks, or {@code Ticks.NO_PRICE} when the side is empty. */
    long bestAskTicks();

    /** Immutable depth view, at most {@code depth} price levels per side. */
    BookSnapshot snapshot(int depth);

    /** Number of resting orders across both sides. */
    int liveOrderCount();

    /** Sum of resting quantity across both sides. Used by invariant tests. */
    long liveQuantity();

    /** Current sequence number. */
    long sequence();

    /** Empties the book without emitting anything. Used by tests and on session end. */
    void clear();
}
