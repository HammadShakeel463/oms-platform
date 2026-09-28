package com.oms.matching.book;

import com.oms.common.domain.Side;
import com.oms.common.event.CancelReason;
import com.oms.common.money.Ticks;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The baseline order book: what a competent Java developer writes first.
 *
 * <p><b>This class exists to make the performance claims reproducible.</b>
 * docs/performance.md says the tuned book is faster and allocates less; without a baseline in
 * the repository that is an assertion. With one, anyone can run
 * {@code OrderBookBenchmark} and check. It is never wired into the running service - the
 * Spring layer only ever constructs {@link PriceTimeOrderBook}.
 *
 * <p>Nothing here is stupid. Every choice is the idiomatic, readable one, and that is the
 * point - these are the four decisions that cost the most, in the order the benchmark
 * isolates them:
 *
 * <ol>
 *   <li><b>{@code BigDecimal} prices.</b> Exact and correct, and the natural type when the
 *       API speaks decimals. Every price comparison is a method call on a heap object holding
 *       a {@code BigInteger} holding an {@code int[]}, and {@code compareTo} may have to
 *       rescale. In a loop that compares prices per level per order, this dominates.</li>
 *   <li><b>{@code ArrayList} per price level.</b> Appending is cheap, but a fill removes from
 *       the <em>front</em>, which shifts the whole array - O(n) per fill.</li>
 *   <li><b>Linear scan to cancel.</b> No id index, so cancelling walks every level and every
 *       order in it: O(levels x orders). Cancels are the most common message on a real
 *       venue, so this is the worst of the four.</li>
 *   <li><b>A {@code List<Fill>} returned per order, and a stream for the FOK check.</b> One
 *       list plus one object per fill, all of it garbage the moment the caller has read it,
 *       plus a stream pipeline whose lambdas and boxing allocate too.</li>
 * </ol>
 *
 * <p>Not thread safe either - the comparison is like-for-like.
 */
public final class NaiveOrderBook implements LimitOrderBook {

    /** A fill, allocated per execution and discarded immediately. */
    record Fill(UUID restingOrderId, String restingAccountId, BigDecimal price, long quantity) {
    }

    /** A resting order. A record would not do: {@code remaining} changes. */
    static final class Order {
        final UUID orderId;
        final String accountId;
        final Side side;
        final BigDecimal price;
        long remaining;

        Order(UUID orderId, String accountId, Side side, BigDecimal price, long remaining) {
            this.orderId = orderId;
            this.accountId = accountId;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
        }
    }

    private final String symbol;
    private final NavigableMap<BigDecimal, List<Order>> bids =
            new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<BigDecimal, List<Order>> asks = new TreeMap<>();

    private long sequence;
    private long liveQuantity;

    public NaiveOrderBook(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String symbol() {
        return symbol;
    }

    @Override
    public long submit(NewOrder order, MatchListener listener) {
        BigDecimal limit = limitOf(order);
        NavigableMap<BigDecimal, List<Order>> contra = order.side() == Side.BUY ? asks : bids;

        if (order.isFillOrKill() && availableQuantity(contra, order, limit) < order.quantity()) {
            listener.onCancelled(symbol, order.orderId(), order.accountId(),
                    CancelReason.FOK_UNFILLABLE, order.quantity(), ++sequence);
            return sequence;
        }

        List<Fill> fills = new ArrayList<>();
        long remaining = order.quantity();

        Iterator<Map.Entry<BigDecimal, List<Order>>> levels = contra.entrySet().iterator();
        while (remaining > 0 && levels.hasNext()) {
            Map.Entry<BigDecimal, List<Order>> entry = levels.next();
            if (!acceptable(order.side(), entry.getKey(), limit)) {
                break;
            }
            List<Order> queue = entry.getValue();

            while (remaining > 0 && !queue.isEmpty()) {
                Order resting = queue.get(0);
                long fill = Math.min(remaining, resting.remaining);

                fills.add(new Fill(resting.orderId, resting.accountId, entry.getKey(), fill));

                remaining -= fill;
                resting.remaining -= fill;
                liveQuantity -= fill;

                if (resting.remaining == 0) {
                    queue.remove(0);   // O(n) array shift on every complete fill
                }
            }
            if (queue.isEmpty()) {
                levels.remove();
            }
        }

        boolean aggressorBuys = order.side() == Side.BUY;
        for (Fill fill : fills) {
            listener.onTrade(symbol, Ticks.fromDecimal(fill.price()), fill.quantity(),
                    order.side(),
                    aggressorBuys ? order.orderId() : fill.restingOrderId(),
                    aggressorBuys ? order.accountId() : fill.restingAccountId(),
                    aggressorBuys ? fill.restingOrderId() : order.orderId(),
                    aggressorBuys ? fill.restingAccountId() : order.accountId(),
                    ++sequence);
        }

        if (remaining == 0) {
            return sequence;
        }
        if (order.isImmediate()) {
            listener.onCancelled(symbol, order.orderId(), order.accountId(),
                    CancelReason.IOC_RESIDUAL, remaining, ++sequence);
            return sequence;
        }

        NavigableMap<BigDecimal, List<Order>> own = aggressorBuys ? bids : asks;
        own.computeIfAbsent(Ticks.toDecimal(order.limitPriceTicks()), p -> new ArrayList<>())
                .add(new Order(order.orderId(), order.accountId(), order.side(),
                        Ticks.toDecimal(order.limitPriceTicks()), remaining));
        liveQuantity += remaining;
        return sequence;
    }

    /** Stream pipeline over BigDecimal totals: allocates, and walks every acceptable level. */
    private long availableQuantity(NavigableMap<BigDecimal, List<Order>> contra,
                                   NewOrder order, BigDecimal limit) {
        return contra.entrySet().stream()
                .filter(e -> acceptable(order.side(), e.getKey(), limit))
                .flatMap(e -> e.getValue().stream())
                .mapToLong(o -> o.remaining)
                .sum();
    }

    private static boolean acceptable(Side side, BigDecimal price, BigDecimal limit) {
        if (limit == null) {
            return true;   // market order
        }
        return side == Side.BUY ? price.compareTo(limit) <= 0 : price.compareTo(limit) >= 0;
    }

    private static BigDecimal limitOf(NewOrder order) {
        return order.limitPriceTicks() == Ticks.NO_PRICE
                ? null
                : Ticks.toDecimal(order.limitPriceTicks());
    }

    /** O(levels x orders): no index, so every level is searched. */
    @Override
    public boolean cancel(UUID orderId, String accountId, MatchListener listener) {
        for (NavigableMap<BigDecimal, List<Order>> side : List.of(bids, asks)) {
            Iterator<Map.Entry<BigDecimal, List<Order>>> levels = side.entrySet().iterator();
            while (levels.hasNext()) {
                List<Order> queue = levels.next().getValue();
                for (int i = 0; i < queue.size(); i++) {
                    Order candidate = queue.get(i);
                    if (candidate.orderId.equals(orderId)) {
                        queue.remove(i);
                        liveQuantity -= candidate.remaining;
                        if (queue.isEmpty()) {
                            levels.remove();
                        }
                        listener.onCancelled(symbol, orderId, candidate.accountId,
                                CancelReason.USER_REQUEST, candidate.remaining, ++sequence);
                        return true;
                    }
                }
            }
        }
        listener.onCancelled(symbol, orderId, accountId,
                CancelReason.UNKNOWN_ORDER, 0L, ++sequence);
        return false;
    }

    @Override
    public long bestBidTicks() {
        return bids.isEmpty() ? Ticks.NO_PRICE : Ticks.fromDecimal(bids.firstKey());
    }

    @Override
    public long bestAskTicks() {
        return asks.isEmpty() ? Ticks.NO_PRICE : Ticks.fromDecimal(asks.firstKey());
    }

    @Override
    public BookSnapshot snapshot(int depth) {
        return BookSnapshot.of(symbol, sequence, bestBidTicks(), bestAskTicks(),
                levels(bids, depth), levels(asks, depth));
    }

    private static List<BookSnapshot.Level> levels(NavigableMap<BigDecimal, List<Order>> side,
                                                   int depth) {
        return side.entrySet().stream()
                .limit(depth)
                .map(e -> new BookSnapshot.Level(
                        Ticks.fromDecimal(e.getKey()),
                        e.getValue().stream().mapToLong(o -> o.remaining).sum(),
                        e.getValue().size()))
                .toList();
    }

    @Override
    public int liveOrderCount() {
        return bids.values().stream().mapToInt(List::size).sum()
                + asks.values().stream().mapToInt(List::size).sum();
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
        liveQuantity = 0L;
    }
}
