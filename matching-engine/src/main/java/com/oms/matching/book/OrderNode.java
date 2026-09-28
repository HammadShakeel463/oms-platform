package com.oms.matching.book;

import java.util.UUID;

/**
 * A resting order, and simultaneously a node in its price level's FIFO queue.
 *
 * <p><b>Intrusive linking.</b> The {@code prev}/{@code next} pointers live on the order
 * itself rather than in a wrapper node owned by a collection. That means removing an order
 * from the middle of its queue is four pointer writes with no lookup and no shifting, and
 * there is one object per resting order rather than two. Exactly the reason you reach for
 * {@code boost::intrusive::list} instead of {@code std::list} in C++ - and the win is bigger
 * here, because every extra object is also extra work for the collector and an extra cache
 * miss on the way to the data.
 *
 * <p>Compare with the obvious Java version, {@code ArrayDeque<Order>} per level: appending is
 * fine, but cancelling an order in the middle is a linear scan plus an array shift, and
 * cancels are the majority of messages on a real venue.
 *
 * <p><b>Mutable and pooled.</b> Fields are reassigned by {@link #reset} so the node can be
 * returned to a free list and reused. Nothing outside the owning book ever holds a reference
 * to one, which is what makes reuse safe - and is why this class is package-private.
 */
final class OrderNode {

    UUID orderId;
    String accountId;
    long priceTicks;
    long remaining;

    /** Arrival sequence, purely for assertions and diagnostics: FIFO order is the list order. */
    long arrivalSeq;

    /** Intrusive queue links within the owning {@link PriceLevel}. */
    OrderNode prev;
    OrderNode next;

    /** Owner, so an unlink needs no search for the level. */
    PriceLevel level;

    void reset(UUID orderId, String accountId, long priceTicks, long quantity, long arrivalSeq) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.priceTicks = priceTicks;
        this.remaining = quantity;
        this.arrivalSeq = arrivalSeq;
        this.prev = null;
        this.next = null;
        this.level = null;
    }

    /**
     * Clears references before the node goes back to the pool.
     *
     * <p>Not hygiene for its own sake: a pooled node holding a stale {@code UUID} and
     * {@code String} keeps those objects alive for as long as the pool does. A pool that
     * retains references is a memory leak that looks like a cache.
     */
    void clear() {
        this.orderId = null;
        this.accountId = null;
        this.priceTicks = 0L;
        this.remaining = 0L;
        this.arrivalSeq = 0L;
        this.prev = null;
        this.next = null;
        this.level = null;
    }

    @Override
    public String toString() {
        return "OrderNode[" + orderId + " qty=" + remaining + " @" + priceTicks + "]";
    }
}
