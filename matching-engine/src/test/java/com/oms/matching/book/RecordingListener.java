package com.oms.matching.book;

import com.oms.common.domain.Side;
import com.oms.common.event.CancelReason;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Captures what a book reported, so a test can assert on it.
 *
 * <p>A hand-written recorder rather than a Mockito mock. The listener has nine parameters on
 * one method; a Mockito verification of that is unreadable, and an argument captor per field
 * is worse. Recording plain records and asserting with AssertJ says what the test means.
 */
public final class RecordingListener implements MatchListener {

    public record Trade(String symbol, long priceTicks, long quantity, Side aggressorSide,
                        UUID buyOrderId, String buyAccountId,
                        UUID sellOrderId, String sellAccountId, long sequence) {
    }

    public record Cancelled(String symbol, UUID orderId, String accountId,
                            CancelReason reason, long quantity, long sequence) {
    }

    private final List<Trade> trades = new ArrayList<>();
    private final List<Cancelled> cancels = new ArrayList<>();

    @Override
    public void onTrade(String symbol, long priceTicks, long quantity, Side aggressorSide,
                        UUID buyOrderId, String buyAccountId,
                        UUID sellOrderId, String sellAccountId, long sequence) {
        trades.add(new Trade(symbol, priceTicks, quantity, aggressorSide,
                buyOrderId, buyAccountId, sellOrderId, sellAccountId, sequence));
    }

    @Override
    public void onCancelled(String symbol, UUID orderId, String accountId,
                            CancelReason reason, long quantity, long sequence) {
        cancels.add(new Cancelled(symbol, orderId, accountId, reason, quantity, sequence));
    }

    public List<Trade> trades() {
        return trades;
    }

    public List<Cancelled> cancels() {
        return cancels;
    }

    public Trade lastTrade() {
        return trades.get(trades.size() - 1);
    }

    public Cancelled lastCancel() {
        return cancels.get(cancels.size() - 1);
    }

    public long totalTradedQuantity() {
        return trades.stream().mapToLong(Trade::quantity).sum();
    }

    /** Every sequence number emitted, trades and cancels together, in emission order. */
    public List<Long> sequences() {
        List<Long> all = new ArrayList<>();
        trades.forEach(t -> all.add(t.sequence()));
        cancels.forEach(c -> all.add(c.sequence()));
        all.sort(Long::compare);
        return all;
    }

    public void clear() {
        trades.clear();
        cancels.clear();
    }
}
