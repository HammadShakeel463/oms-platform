package com.oms.matching.book;

import com.oms.common.domain.Side;
import com.oms.common.event.CancelReason;

import java.util.UUID;

/**
 * Where the book reports what it did.
 *
 * <p><b>Why a flat parameter list instead of returning a list of fill objects.</b> The
 * obvious API is {@code List<Fill> submit(NewOrder)}. It allocates a list plus one object
 * per fill on every order - and a single aggressive order sweeping a deep book produces
 * dozens of fills. That allocation is pure garbage: the caller reads each fill once and
 * drops it.
 *
 * <p>Pushing the values out as primitives means the book allocates <em>nothing</em> while
 * matching, and the decision about whether to allocate belongs to the listener. The
 * production listener builds a {@code TradeExecutedEvent} per fill because it has to put one
 * on the wire; the benchmark listener just increments a counter and allocates nothing at all,
 * which is what makes the benchmark measure the book rather than the event objects.
 *
 * <p>This is the callback-over-container idiom you would reach for in C++ for the same
 * reason - except here the pressure is not a heap allocation per fill but the collector work
 * that allocation causes later, at a time you do not choose.
 *
 * <p>Implementations are called from the single writer thread that owns the book, so they
 * need no synchronisation, and they must not block: the book is held for the duration of the
 * callback and every other order for that symbol is queued behind it.
 */
public interface MatchListener {

    /**
     * Two orders traded.
     *
     * @param priceTicks the RESTING order price - price improvement accrues to the
     *                   aggressor, which is standard continuous-book behaviour
     * @param sequence   strictly increasing per symbol
     */
    void onTrade(String symbol, long priceTicks, long quantity, Side aggressorSide,
                 UUID buyOrderId, String buyAccountId,
                 UUID sellOrderId, String sellAccountId, long sequence);

    /**
     * Quantity left the book without trading: a cancel landed, or an immediate order had a
     * residual, or a fill-or-kill could not be satisfied.
     */
    void onCancelled(String symbol, UUID orderId, String accountId,
                     CancelReason reason, long quantity, long sequence);

    /** Discards everything. Used by benchmarks that measure matching only. */
    MatchListener NO_OP = new MatchListener() {
        @Override
        public void onTrade(String symbol, long priceTicks, long quantity, Side aggressorSide,
                            UUID buyOrderId, String buyAccountId,
                            UUID sellOrderId, String sellAccountId, long sequence) {
        }

        @Override
        public void onCancelled(String symbol, UUID orderId, String accountId,
                                CancelReason reason, long quantity, long sequence) {
        }
    };
}
