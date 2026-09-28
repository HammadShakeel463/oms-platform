package com.oms.matching.engine;

import com.oms.matching.book.BookSnapshot;
import com.oms.matching.book.MatchListener;
import com.oms.matching.book.NewOrder;
import com.oms.matching.config.EngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes messages to per-symbol books and owns the concurrency invariant.
 *
 * <h2>Where the thread safety comes from</h2>
 *
 * <p>The only shared mutable state in this class is the {@code symbol -> BookSlot} map, and
 * it is a {@link ConcurrentHashMap}. Everything inside a {@link BookSlot} is single-writer.
 * That split is the whole design:
 *
 * <pre>
 *   Kafka: all messages for a symbol carry that symbol as the key
 *     -> one partition per symbol
 *       -> one consumer thread per partition
 *         -> one writer per book, for free, with no lock in the matching path
 * </pre>
 *
 * <p>Concurrency across symbols, serialisation within a symbol. The parallelism is real - 12
 * partitions run on up to 12 threads - and no thread ever waits for another, because no two
 * threads ever want the same book.
 *
 * <p>A C++ engine reaches the same place by sharding books across threads by symbol hash and
 * giving each thread an SPSC queue. The shape of the solution is identical; what differs is
 * who maintains it. Here the sharding, the queues, the backpressure and the failover when an
 * instance dies are Kafka's job, and the price is that a partition reassignment can move a
 * book to a different thread - which is why the recovery story below matters.
 *
 * <p><b>The thing to say out loud in an interview: the concurrency win here is a partitioning
 * decision, not a clever lock.</b> A lock-free order book - CAS loops over the level array -
 * is possible and is the wrong tool for this problem. It would buy the ability to have several
 * threads mutate one book, which is a capability nobody wants: matching is inherently
 * sequential, because price-time priority is defined by an order of arrival. Two threads
 * matching the same book concurrently do not make it faster; they make "who was first"
 * ambiguous, which is the one property the book exists to define. See ADR 0005.
 *
 * <h2>Recovery</h2>
 *
 * <p>Books are in memory, so a restart or a rebalance rebuilds them by replaying the
 * partition. That is safe only because trade ids are deterministic - see {@link TradeIds}.
 */
@Component
public class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    /**
     * Symbol to book. Concurrent because different writer threads create and look up entries
     * for different symbols; {@code computeIfAbsent} guarantees exactly one book per symbol
     * even if two threads race on first sight of it.
     *
     * <p>A plain {@code HashMap} would be a real bug rather than a theoretical one: a
     * concurrent resize can corrupt the table, and in earlier JDKs could spin forever.
     */
    private final Map<String, BookSlot> books = new ConcurrentHashMap<>();

    private final EngineProperties properties;

    public MatchingEngine(EngineProperties properties) {
        this.properties = properties;
    }

    /**
     * Matches a new order.
     *
     * <p>Must be called from the writer thread that owns {@code symbol}.
     *
     * @return false if this order was a duplicate and was ignored
     */
    public boolean submit(String symbol, NewOrder order, MatchListener listener) {
        BookSlot slot = slot(symbol);

        if (slot.alreadyProcessed(order.orderId())) {
            log.debug("Ignoring duplicate order {} for {}", order.orderId(), symbol);
            return false;
        }

        slot.book().submit(order, listener);
        return true;
    }

    /**
     * Cancels a resting order.
     *
     * <p>Must be called from the writer thread that owns {@code symbol}. Cancels are
     * deliberately not deduplicated by order id: a second cancel for an order that has left
     * the book reports {@code UNKNOWN_ORDER}, which is already idempotent from the
     * consumer's point of view, and suppressing it would hide a genuine
     * "the engine never had this order" signal.
     */
    public boolean cancel(String symbol, UUID orderId, String accountId, MatchListener listener) {
        return slot(symbol).book().cancel(orderId, accountId, listener);
    }

    /**
     * Republishes the reader-visible snapshot for a symbol.
     *
     * <p>Called once per consumed Kafka batch by the writer thread, not once per order.
     */
    public void publishSnapshot(String symbol) {
        BookSlot slot = books.get(symbol);
        if (slot != null) {
            slot.publishSnapshot();
        }
    }

    /**
     * Depth of book. Safe from any thread - returns the last published immutable snapshot.
     *
     * <p>Reading a symbol with no book returns an empty snapshot rather than creating one:
     * a depth query must never mutate engine state, or a scripted reader could make the
     * engine allocate a book per typo.
     */
    public BookSnapshot snapshot(String symbol) {
        BookSlot slot = books.get(symbol);
        return slot != null ? slot.currentSnapshot() : BookSnapshot.empty(symbol);
    }

    /** Safe from any thread. */
    public Collection<String> symbols() {
        return books.keySet();
    }

    /** Writer thread only. Used when a partition is revoked. */
    public void resetSymbol(String symbol) {
        BookSlot slot = books.get(symbol);
        if (slot != null) {
            log.info("Resetting book for {} - partition revoked or session ended", symbol);
            slot.reset();
        }
    }

    /** Package-visible for metrics and tests. */
    public BookSlot slot(String symbol) {
        return books.computeIfAbsent(symbol, s -> new BookSlot(
                s,
                properties.snapshotDepth(),
                properties.dedupeCapacity(),
                properties.nodePoolLimit()));
    }

    public int bookCount() {
        return books.size();
    }
}
