package com.oms.marketdata.api;

import com.oms.common.event.MarketTickEvent;
import com.oms.marketdata.config.MarketDataProperties;
import com.oms.marketdata.stream.Subscription;
import com.oms.marketdata.stream.TickBroadcaster;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Server-Sent Events stream of quote updates.
 *
 * <h2>Why SSE and not WebSocket</h2>
 *
 * <p>The traffic is one-directional: the server pushes quotes, the client says nothing after the
 * subscribe. SSE is plain HTTP, so it passes through the gateway, the JWT filter and any corporate
 * proxy with no special handling, and the browser reconnects automatically. WebSocket buys
 * bidirectionality that this endpoint has no use for, and costs a protocol upgrade that every
 * intermediary has to be configured for.
 *
 * <h2>Virtual threads, without a single Java 21 API call</h2>
 *
 * <p>Each subscription needs a thread that blocks on its mailbox and writes to the socket. On
 * platform threads that caps concurrent subscribers at the pool size, and a few hundred idle
 * subscribers would each hold a megabyte of stack. Virtual threads are the right tool: a thread
 * parked on a blocking read costs a few hundred bytes of heap.
 *
 * <p>The executor is nonetheless <b>injected</b> as a Spring {@link AsyncTaskExecutor} rather than
 * created here with {@code Executors.newVirtualThreadPerTaskExecutor()}. When
 * {@code spring.threads.virtual.enabled=true} on a Java 21+ runtime, Boot's auto-configured
 * {@code applicationTaskExecutor} is a {@code SimpleAsyncTaskExecutor} backed by virtual threads;
 * with it off it is a platform-thread pool. Same code, same bytecode, and the threading model
 * becomes a deployment choice rather than a compile-time one.
 *
 * <p>Two things that buys, beyond taste. A test can run the delivery loop on platform threads and
 * get deterministic stack traces. And an operator can turn virtual threads off in production
 * without a rebuild if they ever need to - which, for a feature this new, is worth having.
 *
 * <p>(The platform targets Java 21, so virtual threads are guaranteed rather than conditional -
 * see ADR 0006. An earlier version of this class avoided the Java 21 API because the build targeted
 * 17; that constraint is gone, but the injected executor is kept on its own merits.)
 *
 * <h2>What virtual threads do not fix</h2>
 *
 * <p>They raise the ceiling on how many subscribers can be parked; they do nothing about a
 * subscriber that reads slowly. That is the mailbox's job - see {@code ConflatingMailbox}. The two
 * mechanisms solve different halves of the same problem, and either one alone is insufficient:
 * virtual threads with an unbounded queue still runs out of memory, and a conflating queue on a
 * 200-thread pool still caps subscribers at 200.
 */
@RestController
@RequestMapping("/api/v1/quotes")
public class QuoteStreamController {

    private static final Logger log = LoggerFactory.getLogger(QuoteStreamController.class);

    private final TickBroadcaster broadcaster;
    private final AsyncTaskExecutor taskExecutor;
    private final MarketDataProperties properties;
    private final Counter opened;
    private final Counter dropped;

    public QuoteStreamController(TickBroadcaster broadcaster,
                                 AsyncTaskExecutor applicationTaskExecutor,
                                 MarketDataProperties properties,
                                 MeterRegistry meterRegistry) {
        this.broadcaster = broadcaster;
        this.taskExecutor = applicationTaskExecutor;
        this.properties = properties;
        this.opened = meterRegistry.counter("oms.marketdata.stream.opened");
        this.dropped = meterRegistry.counter("oms.marketdata.stream.dropped");
    }

    /**
     * Streams quote updates.
     *
     * @param symbols comma-separated filter; omit for every symbol
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String symbols) {
        Set<String> filter = parseSymbols(symbols);

        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
        Subscription subscription = broadcaster.register(new Subscription(filter));
        opened.increment();

        // All three callbacks unregister. A client can disappear in several ways - clean close,
        // timeout, network error - and every one of them has to release the subscription and let
        // the delivery loop exit. Handling only onCompletion is the standard way to leak an SSE
        // subscription, and it leaks a thread with it.
        emitter.onCompletion(() -> broadcaster.unregister(subscription));
        emitter.onTimeout(() -> {
            broadcaster.unregister(subscription);
            emitter.complete();
        });
        emitter.onError(error -> broadcaster.unregister(subscription));

        taskExecutor.execute(() -> deliver(emitter, subscription));
        return emitter;
    }

    /**
     * The delivery loop: block on the mailbox, write what comes out, repeat until the client goes
     * away or the server-side deadline passes.
     */
    private void deliver(SseEmitter emitter, Subscription subscription) {
        long deadline = System.nanoTime() + properties.streamTimeout().toNanos();
        long heartbeatNanos = properties.streamHeartbeat().toNanos();
        long lastWrite = System.nanoTime();

        try {
            while (subscription.isOpen() && System.nanoTime() < deadline) {
                MarketTickEvent tick = subscription.mailbox()
                        .take(heartbeatNanos, TimeUnit.NANOSECONDS);

                if (tick != null) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(tick.sequence()))
                            .name("quote")
                            .data(QuoteResponse.from(tick), MediaType.APPLICATION_JSON));
                    lastWrite = System.nanoTime();
                } else if (System.nanoTime() - lastWrite >= heartbeatNanos) {
                    // A comment frame. Without it an idle stream looks dead to an intermediate
                    // proxy, which closes it after its own idle timeout - and the client then sees
                    // a connection drop that never happened at the application level.
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastWrite = System.nanoTime();
                }
            }
            emitter.complete();
        } catch (IOException e) {
            // The normal way a stream ends: the client closed the connection. Not an error, and
            // logging it at WARN would fill the log with ordinary browser navigation.
            dropped.increment();
            log.debug("Subscription {} disconnected: {}", subscription.id(), e.getMessage());
            emitter.complete();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (Exception e) {
            log.warn("Subscription {} failed", subscription.id(), e);
            emitter.completeWithError(e);
        } finally {
            broadcaster.unregister(subscription);
        }
    }

    private static Set<String> parseSymbols(String symbols) {
        if (symbols == null || symbols.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(symbols.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
