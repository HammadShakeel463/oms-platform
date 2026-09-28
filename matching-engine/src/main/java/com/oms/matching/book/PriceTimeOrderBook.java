package com.oms.matching.book;

import com.oms.common.domain.Side;
import com.oms.common.event.CancelReason;
import com.oms.common.money.Ticks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The order book that actually runs.
 *
 * <h2>Structure</h2>
 * <pre>
 *   bids: TreeMap&lt;Long, PriceLevel&gt;  (descending - best bid is firstEntry)
 *   asks: TreeMap&lt;Long, PriceLevel&gt;  (ascending  - best ask is firstEntry)
 *   index: HashMap&lt;UUID, OrderNode&gt;  (O(1) cancel)
 *   pool:  ArrayDeque&lt;OrderNode&gt;     (free list)
 * </pre>
 *
 * Each {@link PriceLevel} is an intrusive FIFO of {@link OrderNode}. Price priority comes
 * from the tree ordering, time priority from the queue order within a level.
 *
 * <h2>Why a TreeMap and not something cleverer</h2>
 * <p>The textbook high-performance answer is a flat array indexed by
 * {@code (price - basePrice) / tickSize} - an O(1) price ladder with perfect locality, which
 * is what a C++ engine would use. It is genuinely faster and it is the right end state. It
 * is not here yet for one honest reason: it needs a bounded, known price range per instrument,
 * and handling an order outside the window means either rejecting it or reallocating and
 * rebasing the ladder. That is a real design with real edge cases, and shipping a correct
 * tree beats shipping a ladder that mishandles a limit-up move. docs/performance.md records
 * it as the measured next step rather than pretending it is done.
 *
 * <p>What the TreeMap costs is a red-black tree walk - O(log levels) - on insertion and on
 * finding the best price, against O(1) for the array. With a few hundred live levels that is
 * eight or nine comparisons. What it avoids is a pointer chase per level during a sweep,
 * because a sweep walks the tree in order via {@code firstEntry}, not by random access.
 *
 * <h2>Allocation on the hot path</h2>
 * <p>Matching allocates <b>nothing</b>:
 * <ul>
 *   <li>fills are pushed to a {@link MatchListener} as primitives rather than returned as
 *       objects;</li>
 *   <li>{@link OrderNode} comes from a free list, so a steady state of place-and-cancel
 *       produces no garbage at all;</li>
 *   <li>prices are {@code long} ticks, never {@code BigDecimal} (ADR 0002);</li>
 *   <li>no streams, no lambdas, no iterators in the matching loop - {@code firstEntry()} plus
 *       a direct {@code remove}, because an escaping {@code Iterator} is an allocation and
 *       a lambda capture is another.</li>
 * </ul>
 *
 * <p>The only allocations left in the steady state are the {@link NewOrder} record per
 * incoming message (already paid for by the Kafka deserialiser), a {@link PriceLevel} when a
 * price is touched for the first time, and the {@link BookSnapshot} built for readers - which
 * happens once per batch, not once per order.
 *
 * <p>Why this matters in Java specifically: allocation itself is cheap - a pointer bump - but
 * the cost is deferred to the collector, which runs at a moment you do not choose. A book
 * that allocates per fill shows up not as slower matching but as a worse p99 somewhere else
 * entirely. This is the main way reasoning about performance differs from C++, where
 * {@code new} costs what it costs, where you called it.
 *
 * <p><b>Not thread safe.</b> One writer thread per book (ADR 0005).
 */
public final class PriceTimeOrderBook implements LimitOrderBook {

    private static final int DEFAULT_POOL_LIMIT = 4_096;

    private final String symbol;

    /** Descending: {@code firstEntry()} is the best bid. */
    private final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());

    /** Ascending: {@code firstEntry()} is the best ask. */
    private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();

    /**
     * Order id to node, so a cancel is a hash lookup plus an O(1) unlink instead of a scan
     * of every level. Without it, cancelling is O(levels x orders) - and cancels are the
     * most common message on a real venue.
     */
    private final Map<UUID, OrderNode> index = new HashMap<>();

    /** Free list. Bounded, so a burst cannot make the pool itself the leak. */
    private final ArrayDeque<OrderNode> pool;
    private final int poolLimit;

    private long sequence;
    private long arrivalCounter;
    private long liveQuantity;

    public PriceTimeOrderBook(String symbol) {
        this(symbol, DEFAULT_POOL_LIMIT);
    }

    /**
     * @param poolLimit maximum nodes retained for reuse; 0 disables pooling, which is how
     *                  the benchmark isolates what pooling is worth
     */
    public PriceTimeOrderBook(String symbol, int poolLimit) {
        this.symbol = symbol;
        this.poolLimit = Math.max(0, poolLimit);
        this.pool = new ArrayDeque<>(Math.min(this.poolLimit, 256));
    }

    @Override
    public String symbol() {
        return symbol;
    }

    // =================================================================================
    //  Matching
    // =================================================================================

    @Override
    public long submit(NewOrder order, MatchListener listener) {
        long limit = order.effectiveLimitTicks();
        NavigableMap<Long, PriceLevel> contra = order.side() == Side.BUY ? asks : bids;

        // Fill-or-kill is decided before anything is filled: if the whole quantity cannot
        // trade at acceptable prices, none of it may. Checking first is the only way -
        // matching and then rolling back would mean un-emitting fills.
        if (order.isFillOrKill()
                && availableQuantity(contra, order.side(), limit) < order.quantity()) {
            listener.onCancelled(symbol, order.orderId(), order.accountId(),
                    CancelReason.FOK_UNFILLABLE, order.quantity(), ++sequence);
            return sequence;
        }

        long remaining = match(order, contra, limit, listener);

        if (remaining == 0) {
            return sequence;
        }

        if (order.isImmediate()) {
            // IOC residual, or a market order with no more contra liquidity. A market order
            // never rests: it has no price, so there is no price at which to rest it.
            listener.onCancelled(symbol, order.orderId(), order.accountId(),
                    CancelReason.IOC_RESIDUAL, remaining, ++sequence);
            return sequence;
        }

        rest(order, remaining);
        return sequence;
    }

    /**
     * Walks the contra side while it crosses, filling FIFO within each level.
     *
     * @return quantity left unfilled
     */
    private long match(NewOrder order, NavigableMap<Long, PriceLevel> contra,
                       long limit, MatchListener listener) {
        long remaining = order.quantity();
        Side side = order.side();

        while (remaining > 0 && !contra.isEmpty()) {
            Map.Entry<Long, PriceLevel> best = contra.firstEntry();
            long bestPrice = best.getKey();

            // Side.crosses answers "is this price acceptable for this side", which is the
            // only place the buy/sell asymmetry appears in the loop.
            if (!side.crosses(bestPrice, limit)) {
                break;
            }

            PriceLevel level = best.getValue();

            while (remaining > 0 && level.head != null) {
                OrderNode resting = level.head;
                long fill = Math.min(remaining, resting.remaining);

                // Trade at the RESTING price: the order that was there first set the price,
                // and price improvement accrues to the aggressor.
                emitTrade(order, resting, bestPrice, fill, listener);

                remaining -= fill;
                resting.remaining -= fill;
                level.reduce(fill);
                liveQuantity -= fill;

                if (resting.remaining == 0) {
                    level.unlink(resting);
                    index.remove(resting.orderId);
                    release(resting);
                }
            }

            if (level.isEmpty()) {
                contra.remove(bestPrice);
            }
        }
        return remaining;
    }

    private void emitTrade(NewOrder aggressor, OrderNode resting, long priceTicks,
                           long quantity, MatchListener listener) {
        boolean aggressorBuys = aggressor.side() == Side.BUY;
        listener.onTrade(symbol, priceTicks, quantity, aggressor.side(),
                aggressorBuys ? aggressor.orderId() : resting.orderId,
                aggressorBuys ? aggressor.accountId() : resting.accountId,
                aggressorBuys ? resting.orderId : aggressor.orderId(),
                aggressorBuys ? resting.accountId : aggressor.accountId(),
                ++sequence);
    }

    /**
     * Total quantity available to an order of {@code side} with limit {@code limit}.
     *
     * <p>Only called for fill-or-kill, and it stops as soon as enough is found, so the
     * common case walks one or two levels. Per-level running totals are what make each step
     * O(1) instead of a queue walk.
     */
    private long availableQuantity(NavigableMap<Long, PriceLevel> contra, Side side, long limit) {
        long available = 0;
        for (Map.Entry<Long, PriceLevel> entry : contra.entrySet()) {
            if (!side.crosses(entry.getKey(), limit)) {
                break;
            }
            available += entry.getValue().totalQuantity;
        }
        return available;
    }

    private void rest(NewOrder order, long quantity) {
        NavigableMap<Long, PriceLevel> own = order.side() == Side.BUY ? bids : asks;
        long price = order.limitPriceTicks();

        PriceLevel level = own.get(price);
        if (level == null) {
            level = new PriceLevel(price);
            own.put(price, level);
        }

        OrderNode node = acquire();
        node.reset(order.orderId(), order.accountId(), price, quantity, ++arrivalCounter);
        level.append(node);
        index.put(order.orderId(), node);
        liveQuantity += quantity;
    }

    // =================================================================================
    //  Cancellation
    // =================================================================================

    @Override
    public boolean cancel(UUID orderId, String accountId, MatchListener listener) {
        OrderNode node = index.remove(orderId);

        if (node == null) {
            // Already filled, already cancelled, or never seen. The engine owns the book,
            // so this is an answer rather than an error - order-service decides what it
            // means for its own state.
            listener.onCancelled(symbol, orderId, accountId,
                    CancelReason.UNKNOWN_ORDER, 0L, ++sequence);
            return false;
        }

        // Defence in depth: order-service already scoped the request to the caller's
        // account. Checking again here costs one comparison and means a bug upstream cannot
        // cancel somebody else's order.
        if (accountId != null && !accountId.equals(node.accountId)) {
            index.put(orderId, node);
            listener.onCancelled(symbol, orderId, accountId,
                    CancelReason.UNKNOWN_ORDER, 0L, ++sequence);
            return false;
        }

        long cancelled = node.remaining;
        PriceLevel level = node.level;
        long price = level.priceTicks;

        level.unlink(node);
        liveQuantity -= cancelled;

        if (level.isEmpty()) {
            (bids.get(price) == level ? bids : asks).remove(price);
        }

        String owner = node.accountId;
        release(node);

        listener.onCancelled(symbol, orderId, owner,
                CancelReason.USER_REQUEST, cancelled, ++sequence);
        return true;
    }

    // =================================================================================
    //  Node pool
    // =================================================================================

    private OrderNode acquire() {
        OrderNode node = pool.pollFirst();
        return node != null ? node : new OrderNode();
    }

    private void release(OrderNode node) {
        if (poolLimit == 0) {
            return;
        }
        node.clear();
        if (pool.size() < poolLimit) {
            pool.addFirst(node);
        }
    }

    // =================================================================================
    //  Reads
    // =================================================================================

    @Override
    public long bestBidTicks() {
        return bids.isEmpty() ? Ticks.NO_PRICE : bids.firstKey();
    }

    @Override
    public long bestAskTicks() {
        return asks.isEmpty() ? Ticks.NO_PRICE : asks.firstKey();
    }

    /**
     * Builds an immutable depth view.
     *
     * <p>This is the one place the book allocates on purpose. It is called once per consumed
     * batch, not once per order, so the cost is amortised over however many messages the
     * batch contained - and it is what lets every reader run without a lock.
     */
    @Override
    public BookSnapshot snapshot(int depth) {
        return BookSnapshot.of(symbol, sequence, bestBidTicks(), bestAskTicks(),
                levels(bids, depth), levels(asks, depth));
    }

    private static List<BookSnapshot.Level> levels(NavigableMap<Long, PriceLevel> side, int depth) {
        List<BookSnapshot.Level> out = new ArrayList<>(Math.min(depth, side.size()));
        Iterator<PriceLevel> it = side.values().iterator();
        while (it.hasNext() && out.size() < depth) {
            PriceLevel level = it.next();
            out.add(new BookSnapshot.Level(level.priceTicks, level.totalQuantity, level.orderCount));
        }
        return out;
    }

    @Override
    public int liveOrderCount() {
        return index.size();
    }

    @Override
    public long liveQuantity() {
        return liveQuantity;
    }

    @Override
    public long sequence() {
        return sequence;
    }

    @Override
    public void clear() {
        bids.clear();
        asks.clear();
        index.clear();
        pool.clear();
        liveQuantity = 0L;
    }

    /** Depth in price levels, per side. Exposed for metrics and tests. */
    public int bidLevelCount() {
        return bids.size();
    }

    public int askLevelCount() {
        return asks.size();
    }

    /** Nodes currently held for reuse. Exposed so a test can prove pooling works. */
    public int pooledNodeCount() {
        return pool.size();
    }
}
