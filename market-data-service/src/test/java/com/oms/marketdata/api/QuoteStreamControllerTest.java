package com.oms.marketdata.api;

import com.oms.common.event.MarketTickEvent;
import com.oms.marketdata.config.MarketDataProperties;
import com.oms.marketdata.stream.Subscription;
import com.oms.marketdata.stream.TickBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The SSE quote stream.
 *
 * <p>The thing this test is really protecting is the <b>subscription lifecycle</b>. A client can
 * disappear in three different ways - a clean close, the server-side timeout, or a network error -
 * and each one arrives as a different {@link SseEmitter} callback. Handling only
 * {@code onCompletion} is the standard way to leak an SSE subscription, and because each
 * subscription owns a delivery task it leaks a thread with it. All three callbacks unregister, and
 * so does the {@code finally} in the delivery loop.
 *
 * <p>The executor here is a real one rather than a mock: the delivery loop is the subject, and a
 * mock executor that never runs the task would make every assertion below vacuously true.
 */
class QuoteStreamControllerTest {

    private TickBroadcaster broadcaster;
    private MeterRegistry meterRegistry;
    private AsyncTaskExecutor executor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        broadcaster = new TickBroadcaster(meterRegistry);
        executor = new SimpleAsyncTaskExecutor("stream-test-");
    }

    @Test
    @DisplayName("opening a stream registers exactly one subscription and counts it")
    void openingRegistersASubscription() {
        QuoteStreamController controller = controller(Duration.ofSeconds(2), Duration.ofSeconds(1));

        SseEmitter emitter = controller.stream(null);

        assertThat(emitter).isNotNull();
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
        assertThat(meterRegistry.counter("oms.marketdata.stream.opened").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("closing the subscription exits the delivery loop, which unregisters in its finally")
    void closingTheSubscriptionUnregisters() {
        // Note what this does NOT do: call emitter.complete(). Outside a servlet container
        // nothing dispatches the emitter's onCompletion callback, so completing it here would
        // assert on a path only an integration test can exercise. The mechanism that is
        // observable - and the one that matters, because it is the backstop behind all three
        // callbacks - is the finally in the delivery loop.
        QuoteStreamController controller = controller(Duration.ofSeconds(30), Duration.ofMillis(50));
        controller.stream(null);
        await().atMost(Duration.ofSeconds(2)).until(() -> broadcaster.subscriberCount() == 1);
        Subscription subscription = broadcaster.subscriptions().iterator().next();

        subscription.close();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(broadcaster.subscriberCount())
                        .as("a leaked subscription also leaks the thread delivering to it")
                        .isZero());
    }

    @Test
    @DisplayName("the delivery loop exits on its own when the stream timeout elapses")
    void timeoutEndsTheLoop() {
        QuoteStreamController controller =
                controller(Duration.ofMillis(300), Duration.ofMillis(100));

        controller.stream(null);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(broadcaster.subscriberCount())
                        .as("the loop has its own deadline; it must not depend on the container "
                                + "firing onTimeout")
                        .isZero());
    }

    @Test
    @DisplayName("a matching tick is accepted for delivery; fan-out reports how many got it")
    void ticksAreRoutedToMatchingSubscribers() {
        QuoteStreamController controller = controller(Duration.ofSeconds(3), Duration.ofSeconds(2));
        controller.stream("HBL");
        controller.stream("ENGRO");

        assertThat(broadcaster.publish(tick("HBL", 1L)))
                .as("one of the two subscribers asked for HBL")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a symbol filter is parsed, upper-cased and trimmed")
    void symbolFilterIsParsed() {
        QuoteStreamController controller = controller(Duration.ofSeconds(3), Duration.ofSeconds(2));

        controller.stream(" hbl , engro ,, LUCK ");

        Subscription subscription = broadcaster.subscriptions().iterator().next();
        assertThat(subscription.symbols())
                .as("a filter the client typed in lower case must still match")
                .containsExactlyInAnyOrder("HBL", "ENGRO", "LUCK");
    }

    @Test
    @DisplayName("no filter means every symbol, not no symbols")
    void noFilterMeansEverything() {
        QuoteStreamController controller = controller(Duration.ofSeconds(3), Duration.ofSeconds(2));

        controller.stream(null);
        Subscription subscription = broadcaster.subscriptions().iterator().next();

        assertThat(subscription.symbols()).isEmpty();
        assertThat(subscription.wants("ANYTHING"))
                .as("an empty filter set is the wildcard; treating it as 'match nothing' would "
                        + "make the default subscription silently useless")
                .isTrue();
    }

    @Test
    @DisplayName("a blank filter is treated as no filter")
    void blankFilterIsNoFilter() {
        QuoteStreamController controller = controller(Duration.ofSeconds(3), Duration.ofSeconds(2));

        controller.stream("   ");

        assertThat(broadcaster.subscriptions().iterator().next().symbols()).isEmpty();
    }

    @Test
    @DisplayName("a filtered subscriber is not offered ticks for symbols it did not ask for")
    void filterIsHonouredOnPublish() {
        QuoteStreamController controller = controller(Duration.ofSeconds(3), Duration.ofSeconds(2));
        controller.stream("HBL");
        Subscription subscription = broadcaster.subscriptions().iterator().next();

        assertThat(subscription.wants("HBL")).isTrue();
        assertThat(subscription.wants("ENGRO")).isFalse();
        assertThat(broadcaster.publish(tick("ENGRO", 1L)))
                .as("fan-out must skip a subscriber that did not subscribe to the symbol")
                .isZero();
    }

    @Test
    @DisplayName("several concurrent subscribers each get their own subscription")
    void subscribersAreIndependent() {
        QuoteStreamController controller = controller(Duration.ofSeconds(3), Duration.ofSeconds(2));

        controller.stream("HBL");
        controller.stream("ENGRO");
        controller.stream(null);

        assertThat(broadcaster.subscriberCount()).isEqualTo(3);
        assertThat(broadcaster.subscriptions())
                .extracting(Subscription::id)
                .doesNotHaveDuplicates();
        assertThat(meterRegistry.counter("oms.marketdata.stream.opened").count()).isEqualTo(3.0);
    }

    private QuoteStreamController controller(Duration timeout, Duration heartbeat) {
        return new QuoteStreamController(broadcaster, executor,
                new MarketDataProperties(true, 2, 3, 100,
                        Duration.ofSeconds(30), timeout, heartbeat),
                meterRegistry);
    }

    private static MarketTickEvent tick(String symbol, long sequence) {
        return new MarketTickEvent("e-" + sequence, Instant.parse("2026-09-28T09:15:30Z"), 1,
                symbol, 1_724_000L, 1_000, 1_725_000L, 800, 1_724_500L, 200, sequence);
    }
}
