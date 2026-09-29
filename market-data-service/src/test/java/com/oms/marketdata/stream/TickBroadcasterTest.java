package com.oms.marketdata.stream;

import com.oms.common.event.MarketTickEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TickBroadcasterTest {

    private TickBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new TickBroadcaster(new SimpleMeterRegistry());
    }

    private static MarketTickEvent tick(String symbol, long sequence) {
        return new MarketTickEvent(UUID.randomUUID().toString(), Instant.now(), 1, symbol,
                1_720_000, 100, 1_720_200, 100, 1_720_100, 50, sequence);
    }

    @Test
    @DisplayName("a subscription with no filter receives every symbol")
    void unfilteredSubscriptionGetsEverything() throws Exception {
        Subscription subscription = broadcaster.register(new Subscription(Set.of()));

        assertThat(broadcaster.publish(tick("HBL", 1))).isEqualTo(1);
        assertThat(broadcaster.publish(tick("OGDC", 1))).isEqualTo(1);

        assertThat(subscription.mailbox().take(50, TimeUnit.MILLISECONDS).symbol()).isEqualTo("HBL");
        assertThat(subscription.mailbox().take(50, TimeUnit.MILLISECONDS).symbol()).isEqualTo("OGDC");
    }

    @Test
    @DisplayName("a filtered subscription receives only what it asked for")
    void filteredSubscriptionIsFiltered() throws Exception {
        Subscription subscription = broadcaster.register(new Subscription(Set.of("HBL")));

        assertThat(broadcaster.publish(tick("OGDC", 1)))
                .as("nothing was offered, so the tick reached no subscriber")
                .isZero();
        assertThat(broadcaster.publish(tick("HBL", 1))).isEqualTo(1);

        assertThat(subscription.mailbox().take(50, TimeUnit.MILLISECONDS).symbol()).isEqualTo("HBL");
        assertThat(subscription.mailbox().take(50, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("every open subscription gets its own copy")
    void fanOutReachesAllSubscribers() {
        broadcaster.register(new Subscription(Set.of()));
        broadcaster.register(new Subscription(Set.of()));
        broadcaster.register(new Subscription(Set.of("HBL")));
        broadcaster.register(new Subscription(Set.of("LUCK")));

        assertThat(broadcaster.publish(tick("HBL", 1))).isEqualTo(3);
        assertThat(broadcaster.subscriberCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("unregistering removes the subscription and closes it")
    void unregisterClosesTheSubscription() {
        Subscription subscription = broadcaster.register(new Subscription(Set.of()));

        broadcaster.unregister(subscription);

        assertThat(broadcaster.subscriberCount()).isZero();
        assertThat(subscription.isOpen()).isFalse();
        assertThat(broadcaster.publish(tick("HBL", 1))).isZero();
    }

    @Test
    @DisplayName("a closed subscription is reaped on the next publish")
    void closedSubscriptionsAreReapedLazily() {
        Subscription subscription = broadcaster.register(new Subscription(Set.of()));
        // Closed without unregistering: what happens when an HTTP thread dies without running
        // its completion callback.
        subscription.close();

        broadcaster.publish(tick("HBL", 1));

        assertThat(broadcaster.subscriberCount())
                .as("otherwise the entry leaks for ever")
                .isZero();
    }

    @Test
    @DisplayName("a slow subscriber is conflated and never blocks the producer")
    void slowSubscriberDoesNotBlockTheProducer() {
        Subscription fast = broadcaster.register(new Subscription(Set.of()));
        Subscription slow = broadcaster.register(new Subscription(Set.of()));

        for (long sequence = 1; sequence <= 10_000; sequence++) {
            broadcaster.publish(tick("HBL", sequence));
        }

        assertThat(slow.mailbox().pendingSymbols())
                .as("bounded by the symbol universe no matter how far behind the subscriber is")
                .isEqualTo(1);
        assertThat(slow.mailbox().conflatedCount()).isEqualTo(9_999);
        assertThat(fast.mailbox().conflatedCount()).isEqualTo(9_999);
    }

    @Test
    @DisplayName("the producer keeps running at full speed while subscribers come and go")
    void publishingIsSafeWhileSubscriptionsChurn() throws Exception {
        AtomicLong published = new AtomicLong();
        AtomicLong churned = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_000);

        // The producer, as the tick generator thread.
        pool.submit(() -> {
            try {
                start.await();
                long sequence = 0;
                while (System.nanoTime() < deadline) {
                    broadcaster.publish(tick("HBL", ++sequence));
                    published.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        // Two HTTP threads subscribing and unsubscribing, as real clients do.
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (System.nanoTime() < deadline) {
                        Subscription subscription =
                                broadcaster.register(new Subscription(Set.of("HBL")));
                        Thread.yield();
                        broadcaster.unregister(subscription);
                        churned.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) {
            throw new AssertionError("concurrent churn failed", failure.get());
        }

        // Weakly consistent iteration over a ConcurrentHashMap is exactly what is wanted: a
        // subscription added mid-fan-out may miss this tick and will certainly get the next one.
        assertThat(published.get()).isGreaterThan(10_000);
        assertThat(churned.get()).isPositive();
        assertThat(broadcaster.subscriberCount()).isZero();
    }
}
