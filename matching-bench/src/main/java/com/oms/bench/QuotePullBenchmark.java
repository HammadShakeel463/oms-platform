package com.oms.bench;

import com.oms.matching.book.LimitOrderBook;
import com.oms.matching.book.MatchListener;
import com.oms.matching.book.NaiveOrderBook;
import com.oms.matching.book.NewOrder;
import com.oms.matching.book.PriceTimeOrderBook;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Quote and pull: post an order, then cancel it, against a book of realistic depth.
 *
 * <p>This is the single most representative operation on a real venue, and the one the two
 * implementations differ on most. Market makers post and pull continuously; the large majority
 * of messages an exchange receives never result in a trade. If a book is slow at anything, this
 * is where it hurts.
 *
 * <p>It also isolates one specific design decision: the {@code HashMap<UUID, OrderNode>} index.
 * With it, a cancel is a hash lookup plus four pointer writes. Without it - the baseline - a
 * cancel walks every price level and every order in each one until it finds the id. The
 * benchmark is parameterised on book depth so the difference between O(1) and O(n) is visible
 * as a slope rather than as a single number.
 *
 * <p>The workload is a genuine steady state: every invocation adds exactly one order and
 * removes exactly one, so the book stays at its pre-loaded depth for the whole run and no
 * periodic rebuild is needed. Reusing an order id after it has been cancelled is harmless,
 * because a cancelled order is no longer in the book - which is why this benchmark can run
 * indefinitely without touching its state.
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 6, time = 1)
@Fork(value = 2, jvmArgs = {
        "-Xms1g", "-Xmx1g",
        "-XX:+AlwaysPreTouch",
        "-XX:+UseSerialGC"
})
public class QuotePullBenchmark {

    @State(Scope.Thread)
    public static class QuoteState {

        @Param({"TUNED", "TUNED_UNPOOLED", "NAIVE"})
        public BookState.Implementation implementation;

        /** Resting orders per side. The variable the cancel cost depends on. */
        @Param({"200", "2000"})
        public int restingPerSide;

        private static final int QUOTE_POOL = 4_096;

        LimitOrderBook book;
        NewOrder[] quotes;
        int cursor;

        @Setup(Level.Trial)
        public void generateQuotes() {
            // A pool of passive orders to post and pull, generated once.
            Workload workload = Workload.generate(QUOTE_POOL * 4, 99L);
            quotes = new NewOrder[QUOTE_POOL];
            int found = 0;
            for (int i = 0; i < workload.length && found < QUOTE_POOL; i++) {
                NewOrder order = workload.orders[i];
                if (workload.kind[i] == Workload.SUBMIT
                        && order.type() == com.oms.common.domain.OrderType.LIMIT
                        && order.timeInForce() == com.oms.common.domain.TimeInForce.DAY) {
                    quotes[found++] = order;
                }
            }
        }

        @Setup(Level.Iteration)
        public void newBook() {
            book = switch (implementation) {
                case TUNED -> new PriceTimeOrderBook("HBL");
                case TUNED_UNPOOLED -> new PriceTimeOrderBook("HBL", 0);
                case NAIVE -> new NaiveOrderBook("HBL");
            };
            for (NewOrder order : Workload.restingDepth(restingPerSide, 25, 7L)) {
                book.submit(order, MatchListener.NO_OP);
            }
            cursor = 0;
        }
    }

    /**
     * One post-and-pull cycle. Counted as one operation, though it is two book calls - the
     * pair is the unit that means something.
     */
    @Benchmark
    public void quoteAndPull(QuoteState state, Blackhole blackhole) {
        NewOrder quote = state.quotes[state.cursor];
        state.cursor = (state.cursor + 1) & (state.quotes.length - 1);

        blackhole.consume(state.book.submit(quote, MatchListener.NO_OP));
        blackhole.consume(state.book.cancel(quote.orderId(), quote.accountId(), MatchListener.NO_OP));
    }
}
