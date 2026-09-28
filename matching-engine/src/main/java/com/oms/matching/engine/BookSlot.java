package com.oms.matching.engine;

import com.oms.matching.book.BookSnapshot;
import com.oms.matching.book.PriceTimeOrderBook;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One symbol's book, plus the state that surrounds it.
 *
 * <h2>The concurrency contract, in one place</h2>
 *
 * <p>The mutable book is touched by exactly one thread - the Kafka consumer thread that owns
 * the partition this symbol keys to. Readers (the depth REST endpoint, the metrics gauges)
 * never touch it. They read {@link #currentSnapshot()}, which returns an immutable object
 * published through a {@code volatile} field.
 *
 * <p><b>Why that is sufficient, in Java Memory Model terms.</b> A write to a volatile field
 * is a release; a read of it is an acquire. Everything the writer did before the volatile
 * write - constructing the snapshot, filling its lists - happens-before anything a reader
 * does after the volatile read. So a reader that sees the new reference is guaranteed to see
 * a fully constructed object, with no partially visible fields.
 *
 * <p>For a C++ reader this is exactly {@code std::atomic<T*>} with
 * {@code store(release)}/{@code load(acquire)}, and the reasoning transfers directly. Two
 * differences are worth holding onto. First, Java has no relaxed ordering on volatile: every
 * volatile access is at least acquire/release, and sequentially consistent with respect to
 * other volatile accesses. If you want the weaker orderings you go to {@code VarHandle}
 * ({@code getAcquire}, {@code setRelease}, {@code getOpaque}) - which is the right tool, and
 * unnecessary here because this write happens once per batch, not once per order. Second, the
 * reclamation problem simply does not exist: there is no moment where the writer must decide
 * whether a reader is still looking at the old snapshot, because the collector answers that.
 * No hazard pointers, no epoch-based reclamation, no {@code shared_ptr} refcount traffic on
 * the read path. This is the one place where the managed runtime is unambiguously ahead.
 *
 * <h2>Why not a lock</h2>
 *
 * <p>A {@code ReentrantReadWriteLock} around the book would work and would be slower and
 * worse: readers would contend with the writer, a burst of depth queries could starve
 * matching, and the writer would take a lock on every single order to protect against a
 * reader that arrives a few times a second. A {@code StampedLock} optimistic read would avoid
 * most of that - it is a sequence lock, the same construct you would hand-roll in C++ - but
 * it makes readers retry under load and still requires the reader to walk live mutable
 * structures. Publishing an immutable snapshot removes the interaction entirely: the writer
 * never waits for a reader, and a reader never retries.
 *
 * <p>The cost is staleness bounded by the publication interval - a snapshot is at most one
 * consumed batch old. For depth-of-book display that is the right trade; nothing in the
 * matching path reads it.
 */
public final class BookSlot {

    private final PriceTimeOrderBook book;

    /**
     * Written only by the owning writer thread, read by anyone.
     *
     * <p>Not {@code final} and not guarded by a lock: {@code volatile} is doing the entire
     * job. Dropping the keyword would be a data race, and in Java a data race on a reference
     * does not merely risk staleness - a reader could observe the reference before the
     * object's fields are visible.
     */
    private volatile BookSnapshot snapshot;

    /**
     * Order ids this book has already processed, bounded and least-recently-used.
     *
     * <p>Needed because delivery is at-least-once: the same {@code OrderAcceptedEvent} can
     * arrive twice, and matching it twice would create quantity out of nothing. Live orders
     * are already in the book's own index, but a fully filled order has left it, so the book
     * alone cannot answer "have I seen this".
     *
     * <p>Bounded on purpose. An unbounded set of every order id the engine has ever seen is a
     * memory leak with a respectable name. The bound means a duplicate delivered after more
     * than {@code capacity} intervening orders would slip through - acceptable, because Kafka
     * duplicates arrive from a retry or a rebalance and are separated by a handful of
     * records, not by a hundred thousand. Accessed only by the writer thread, so a plain
     * {@code LinkedHashMap} is correct and no concurrent structure is needed.
     */
    private final Map<UUID, Boolean> processedOrders;

    private final int snapshotDepth;

    public BookSlot(String symbol, int snapshotDepth, int dedupeCapacity, int poolLimit) {
        this.book = new PriceTimeOrderBook(symbol, poolLimit);
        this.snapshotDepth = snapshotDepth;
        this.snapshot = BookSnapshot.empty(symbol);
        this.processedOrders = new LinkedHashMap<>(Math.min(dedupeCapacity, 1 << 14), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                return size() > dedupeCapacity;
            }
        };
    }

    /** Writer thread only. */
    public PriceTimeOrderBook book() {
        return book;
    }

    /** Safe for any thread. Never null. */
    public BookSnapshot currentSnapshot() {
        return snapshot;
    }

    /**
     * Writer thread only. Rebuilds the reader-visible view.
     *
     * <p>Called once per consumed batch rather than once per order: the snapshot is the only
     * allocation the book makes on purpose, and amortising it over a batch is what keeps it
     * off the per-order cost.
     */
    public void publishSnapshot() {
        this.snapshot = book.snapshot(snapshotDepth);
    }

    /**
     * Writer thread only.
     *
     * @return true if this order id has already been processed and must be ignored
     */
    public boolean alreadyProcessed(UUID orderId) {
        return processedOrders.putIfAbsent(orderId, Boolean.TRUE) != null;
    }

    /** Writer thread only. Used when a partition is revoked and the book is discarded. */
    public void reset() {
        book.clear();
        processedOrders.clear();
        this.snapshot = BookSnapshot.empty(book.symbol());
    }
}
