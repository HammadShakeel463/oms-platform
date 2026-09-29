package com.oms.marketdata.stream;

import com.oms.common.event.MarketTickEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A per-subscriber mailbox that holds <b>at most one tick per symbol</b>: the most recent one.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>A market data feed produces ticks faster than a slow subscriber can consume them. There are
 * exactly four things a server can do about it, and three of them are wrong:
 *
 * <table>
 *   <tr><th>Strategy</th><th>What happens</th></tr>
 *   <tr><td><b>Unbounded queue</b></td>
 *       <td>Memory grows until the process dies. The subscriber that caused it is fine; everyone
 *           else is killed by the OOM. This is the default behaviour of almost every naive
 *           implementation, and it is the worst option because it fails the whole service rather
 *           than the one client at fault.</td></tr>
 *   <tr><td><b>Block the producer</b></td>
 *       <td>One slow subscriber stalls tick generation for every subscriber. Textbook backpressure
 *           applied to the wrong kind of stream - correct for a work queue, catastrophic for a
 *           broadcast.</td></tr>
 *   <tr><td><b>Bounded queue, drop newest</b></td>
 *       <td>Bounded, but the subscriber is left holding <em>stale</em> quotes while fresh ones are
 *           discarded. Worse than useless for pricing: a quote that is known to be old is more
 *           dangerous than no quote.</td></tr>
 *   <tr><td><b>Conflate - drop oldest, per symbol</b></td>
 *       <td>Memory is bounded by the number of <em>symbols</em>, not by the tick rate, and a
 *           subscriber that falls behind gets the freshest price for every symbol it missed.</td></tr>
 * </table>
 *
 * <p>The fourth is correct here, and it is correct because of a property of this specific data:
 * <b>a tick supersedes its predecessor</b>. Nobody pricing an order cares what the bid was 40
 * milliseconds ago; they care what it is now. Tick streams are conflatable in a way that trade
 * streams emphatically are not - which is why {@code oms.trades.executed.v1} is consumed with
 * at-least-once delivery and full ordering, and this stream is not.
 *
 * <p>The published sequence number is what makes it honest: a subscriber that sees
 * {@code sequence} jump from 41 to 58 knows it was conflated and by how much. Silent conflation
 * would be a lie; conflation with a sequence number is a documented delivery model.
 *
 * <h2>Structure</h2>
 *
 * <pre>
 *   latest : ConcurrentHashMap&lt;symbol, tick&gt;   last value per symbol, overwritten in place
 *   dirty  : LinkedBlockingQueue&lt;symbol&gt;       which symbols have something unread
 *   queued : Set&lt;symbol&gt;                       guard so a symbol is enqueued at most once
 * </pre>
 *
 * <p>The {@code queued} set is what bounds the queue. Without it, a symbol ticking 1,000 times
 * while the subscriber is stalled would put 1,000 entries in {@code dirty} - bounded memory for
 * the ticks, unbounded for the notifications, which is the same bug wearing a hat. With it,
 * {@code dirty} can never hold more entries than there are symbols.
 *
 * <p>Multi-producer, single-consumer. The producer is whichever thread published the tick; the
 * consumer is the one virtual thread that owns this subscription.
 */
public final class ConflatingMailbox {

    private final ConcurrentHashMap<String, MarketTickEvent> latest = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<String> dirty = new LinkedBlockingQueue<>();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong conflated = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();

    /**
     * Offers a tick. Never blocks, never fails, never grows without bound.
     *
     * @return true if this tick replaced an unread one, i.e. the subscriber was conflated
     */
    public boolean offer(MarketTickEvent tick) {
        offered.incrementAndGet();
        latest.put(tick.symbol(), tick);

        // add() returns false when the symbol is already flagged, which means the previous tick
        // for it was never read. That is exactly the definition of a conflation event.
        boolean alreadyPending = !queued.add(tick.symbol());
        if (alreadyPending) {
            conflated.incrementAndGet();
            return true;
        }
        dirty.offer(tick.symbol());
        return false;
    }

    /**
     * Takes the freshest tick for the next symbol with something unread, waiting up to
     * {@code timeout}.
     *
     * <p>The ordering of the two operations matters and is the one subtle part of this class. The
     * symbol is cleared from {@code queued} <em>before</em> {@code latest} is read. If it were
     * cleared afterwards, a tick arriving in between would set {@code latest} and find the symbol
     * still flagged, so it would not enqueue - and it would never be delivered. Clearing first
     * means the worst case is a spurious extra enqueue, which costs one redundant delivery of a
     * value that is still current. Lost updates are unacceptable; a duplicate is harmless.
     *
     * @return the tick, or null if nothing arrived within the timeout
     */
    public MarketTickEvent take(long timeout, TimeUnit unit) throws InterruptedException {
        String symbol = dirty.poll(timeout, unit);
        if (symbol == null) {
            return null;
        }
        queued.remove(symbol);
        MarketTickEvent tick = latest.get(symbol);
        if (tick != null) {
            delivered.incrementAndGet();
        }
        return tick;
    }

    /** Ticks offered to this subscriber. */
    public long offeredCount() {
        return offered.get();
    }

    /** Ticks that superseded an unread one - the measure of how far behind this subscriber is. */
    public long conflatedCount() {
        return conflated.get();
    }

    public long deliveredCount() {
        return delivered.get();
    }

    /** Symbols currently holding something unread. Bounded by the symbol universe. */
    public int pendingSymbols() {
        return dirty.size();
    }

    public void clear() {
        dirty.clear();
        queued.clear();
        latest.clear();
    }
}
