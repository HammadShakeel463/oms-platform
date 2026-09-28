package com.oms.bench;

import com.oms.matching.book.LimitOrderBook;
import com.oms.matching.book.MatchListener;
import com.oms.matching.book.NaiveOrderBook;
import com.oms.matching.book.NewOrder;
import com.oms.matching.book.PriceTimeOrderBook;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark state: the book under test, the pre-generated workload, and a cursor.
 *
 * <p>{@code Scope.Thread} because a book is single-writer by design (ADR 0005). Sharing one
 * book across benchmark threads would measure a data race, not a book - and would be
 * measuring a configuration the engine never runs in.
 */
@State(Scope.Thread)
public class BookState {

    public enum Implementation {
        /** The book that ships: long ticks, intrusive lists, O(1) cancel index, pooled nodes. */
        TUNED,
        /** The same book with node pooling switched off, to isolate what pooling is worth. */
        TUNED_UNPOOLED,
        /** The baseline: BigDecimal prices, ArrayList levels, linear-scan cancel. */
        NAIVE
    }

    @Param({"TUNED", "TUNED_UNPOOLED", "NAIVE"})
    public Implementation implementation;

    /**
     * Resting orders pre-loaded per side before measurement starts.
     *
     * <p>Parameterised because book depth is the variable the implementations differ on. A cold
     * empty book makes them all look the same: there is nothing to walk, and a linear-scan
     * cancel is as fast as an indexed one when there is one order in the book.
     */
    @Param({"200", "2000"})
    public int restingPerSide;

    /** Long enough that a measurement iteration rarely wraps. */
    static final int WORKLOAD_LENGTH = 200_000;
    static final long WORKLOAD_SEED = 20260929L;

    Workload workload;
    NewOrder[] depth;
    LimitOrderBook book;
    int cursor;

    /**
     * Generated once per trial. The workload is immutable and identical for every
     * implementation, so all three are fed byte-identical input.
     */
    @Setup(Level.Trial)
    public void generateWorkload() {
        workload = Workload.generate(WORKLOAD_LENGTH, WORKLOAD_SEED);
        depth = Workload.restingDepth(restingPerSide, 25, WORKLOAD_SEED + 1);
    }

    /**
     * A fresh book per iteration, pre-loaded to the target depth.
     *
     * <p>Per iteration rather than per invocation: {@code Level.Invocation} setup around an
     * operation that costs tens of nanoseconds would dominate what it is measuring, and JMH
     * warns about exactly that. Per iteration keeps the setup cost outside the measured region
     * while still stopping state from drifting across iterations.
     */
    @Setup(Level.Iteration)
    public void newBook() {
        book = switch (implementation) {
            case TUNED -> new PriceTimeOrderBook("HBL");
            case TUNED_UNPOOLED -> new PriceTimeOrderBook("HBL", 0);
            case NAIVE -> new NaiveOrderBook("HBL");
        };
        for (NewOrder order : depth) {
            book.submit(order, MatchListener.NO_OP);
        }
        cursor = 0;
    }

    /**
     * Applies the next operation in the stream.
     *
     * <p>The {@link Blackhole} consumes the result so the JIT cannot decide the whole call is
     * dead code and delete it. This is the single most common way a Java microbenchmark
     * measures nothing at all and reports an impressive number for it.
     */
    public void nextOperation(Blackhole blackhole) {
        int i = cursor++;
        if (cursor == workload.length) {
            // Wrapping would re-submit order ids the book has already seen. Rebuilding is the
            // honest fix; it happens roughly once per 200,000 operations, which is far below
            // the p99.99 this harness reports.
            newBook();
            i = 0;
            cursor = 1;
        }

        if (workload.kind[i] == Workload.CANCEL) {
            NewOrder target = workload.orders[i];
            blackhole.consume(book.cancel(target.orderId(), target.accountId(), MatchListener.NO_OP));
        } else {
            blackhole.consume(book.submit(workload.orders[i], MatchListener.NO_OP));
        }
    }
}
