package com.oms.marketdata.stream;

import com.oms.common.event.MarketTickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backpressure mechanism.
 *
 * <p>The properties under test are the ones that distinguish this from the three wrong answers:
 * memory bounded by the symbol universe rather than the tick rate, the <em>freshest</em> value
 * surviving rather than the oldest, the producer never blocking, and conflation being counted so it
 * is visible instead of silent.
 */
class ConflatingMailboxTest {

    private static MarketTickEvent tick(String symbol, long sequence, long bid) {
        return new MarketTickEvent(UUID.randomUUID().toString(), Instant.now(), 1, symbol,
                bid, 100, bid + 200, 100, bid + 100, 50, sequence);
    }

    @Test
    @DisplayName("a tick offered and taken comes back unchanged")
    void roundTrip() throws Exception {
        ConflatingMailbox mailbox = new ConflatingMailbox();
        MarketTickEvent offered = tick("HBL", 1, 1_720_000);

        assertThat(mailbox.offer(offered)).isFalse();
        assertThat(mailbox.take(100, TimeUnit.MILLISECONDS)).isEqualTo(offered);
    }

    @Test
    @DisplayName("an empty mailbox returns null after the timeout rather than blocking for ever")
    void emptyMailboxTimesOut() throws Exception {
        assertThat(new ConflatingMailbox().take(50, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("a second tick for the same symbol REPLACES the first - the freshest wins")
    void conflationKeepsTheNewest() throws Exception {
        ConflatingMailbox mailbox = new ConflatingMailbox();

        assertThat(mailbox.offer(tick("HBL", 1, 1_720_000))).isFalse();
        assertThat(mailbox.offer(tick("HBL", 2, 1_730_000))).isTrue();
        MarketTickEvent third = tick("HBL", 3, 1_740_000);
        assertThat(mailbox.offer(third)).isTrue();

        MarketTickEvent taken = mailbox.take(100, TimeUnit.MILLISECONDS);
        assertThat(taken)
                .as("a slow subscriber wants the current price, not a faithful replay of stale ones")
                .isEqualTo(third);
        assertThat(taken.sequence()).isEqualTo(3);

        // And nothing else is waiting: the superseded ticks are gone, not queued behind it.
        assertThat(mailbox.take(50, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("the sequence jump is what tells a client it was conflated")
    void sequenceGapIsObservable() throws Exception {
        ConflatingMailbox mailbox = new ConflatingMailbox();

        mailbox.offer(tick("HBL", 41, 1_720_000));
        MarketTickEvent first = mailbox.take(100, TimeUnit.MILLISECONDS);

        for (long sequence = 42; sequence <= 58; sequence++) {
            mailbox.offer(tick("HBL", sequence, 1_720_000 + sequence));
        }
        MarketTickEvent second = mailbox.take(100, TimeUnit.MILLISECONDS);

        assertThat(first.sequence()).isEqualTo(41);
        assertThat(second.sequence())
                .as("41 -> 58 tells the client 16 updates were superseded. Conflation without a "
                        + "sequence number would be a silent lie")
                .isEqualTo(58);
        assertThat(mailbox.conflatedCount()).isEqualTo(16);
    }

    @Test
    @DisplayName("memory is bounded by the number of SYMBOLS, not by the tick rate")
    void memoryIsBoundedBySymbolCount() {
        ConflatingMailbox mailbox = new ConflatingMailbox();
        List<String> symbols = List.of("HBL", "OGDC", "LUCK", "ENGRO", "PSO");

        // 100,000 ticks offered to a subscriber that never reads. An unbounded queue would be
        // holding 100,000 objects here, and the notification queue would hold 100,000 more.
        for (int i = 0; i < 20_000; i++) {
            for (String symbol : symbols) {
                mailbox.offer(tick(symbol, i, 1_000_000 + i));
            }
        }

        assertThat(mailbox.offeredCount()).isEqualTo(100_000);
        assertThat(mailbox.pendingSymbols())
                .as("this is the whole design: bounded by the symbol universe")
                .isEqualTo(symbols.size());
        assertThat(mailbox.conflatedCount()).isEqualTo(100_000 - symbols.size());
    }

    @Test
    @DisplayName("every symbol is delivered once per drain, in first-dirty order")
    void multipleSymbolsAreAllDelivered() throws Exception {
        ConflatingMailbox mailbox = new ConflatingMailbox();
        mailbox.offer(tick("HBL", 1, 1_720_000));
        mailbox.offer(tick("OGDC", 1, 2_050_000));
        mailbox.offer(tick("LUCK", 1, 9_150_000));

        List<String> drained = new ArrayList<>();
        MarketTickEvent taken;
        while ((taken = mailbox.take(50, TimeUnit.MILLISECONDS)) != null) {
            drained.add(taken.symbol());
        }

        assertThat(drained).containsExactly("HBL", "OGDC", "LUCK");
    }

    @Test
    @DisplayName("conflating one symbol does not starve another")
    void busySymbolDoesNotStarveQuietOne() throws Exception {
        ConflatingMailbox mailbox = new ConflatingMailbox();

        for (long sequence = 1; sequence <= 5_000; sequence++) {
            mailbox.offer(tick("HBL", sequence, 1_720_000));
        }
        mailbox.offer(tick("TRG", 1, 587_500));

        List<String> drained = new ArrayList<>();
        MarketTickEvent taken;
        while ((taken = mailbox.take(50, TimeUnit.MILLISECONDS)) != null) {
            drained.add(taken.symbol());
        }

        assertThat(drained)
                .as("a quiet symbol must still get through behind a busy one")
                .containsExactlyInAnyOrder("HBL", "TRG");
    }

    @Test
    @DisplayName("clear releases everything")
    void clearEmptiesTheMailbox() throws Exception {
        ConflatingMailbox mailbox = new ConflatingMailbox();
        mailbox.offer(tick("HBL", 1, 1_720_000));

        mailbox.clear();

        assertThat(mailbox.pendingSymbols()).isZero();
        assertThat(mailbox.take(50, TimeUnit.MILLISECONDS)).isNull();
    }

    /**
     * The race the implementation is written to avoid: a tick arriving between the consumer
     * clearing the dirty flag and reading the value.
     *
     * <p>{@code take} clears {@code queued} <em>before</em> reading {@code latest}, so the worst
     * outcome is a redundant delivery of a still-current value. The other order would lose the
     * update entirely - the tick would set {@code latest}, find the symbol still flagged, decline to
     * enqueue, and never be delivered.
     *
     * <p>As with any concurrency test, this cannot prove the absence of the race; it demonstrates
     * that under sustained contention nothing is lost, which is the testable half.
     */
    @Test
    @DisplayName("under contention the last offered tick is always eventually delivered")
    void noLostUpdatesUnderContention() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            ConflatingMailbox mailbox = new ConflatingMailbox();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicLong lastOffered = new AtomicLong();
            AtomicLong lastSeen = new AtomicLong();
            int ticks = 5_000;

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);

            pool.submit(() -> {
                try {
                    start.await();
                    for (long sequence = 1; sequence <= ticks; sequence++) {
                        mailbox.offer(tick("HBL", sequence, 1_720_000 + sequence));
                        lastOffered.set(sequence);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            pool.submit(() -> {
                try {
                    start.await();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (System.nanoTime() < deadline) {
                        MarketTickEvent taken = mailbox.take(20, TimeUnit.MILLISECONDS);
                        if (taken != null) {
                            lastSeen.set(taken.sequence());
                        }
                        if (lastOffered.get() == ticks && lastSeen.get() == ticks) {
                            break;
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            if (failure.get() != null) {
                throw new AssertionError("contention run failed", failure.get());
            }

            // Drain anything still pending, as a real consumer would on its next pass.
            MarketTickEvent remaining;
            while ((remaining = mailbox.take(20, TimeUnit.MILLISECONDS)) != null) {
                lastSeen.set(remaining.sequence());
            }

            assertThat(lastSeen.get())
                    .as("attempt %d: the final tick must not be lost to the clear/read race", attempt)
                    .isEqualTo(ticks);
        }
    }
}
