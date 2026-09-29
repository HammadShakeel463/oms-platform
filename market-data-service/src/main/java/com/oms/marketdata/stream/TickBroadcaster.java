package com.oms.marketdata.stream;

import com.oms.common.event.MarketTickEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fans ticks out to every open subscription, without ever blocking the producer.
 *
 * <p>The publish path is: for each subscription, offer into its conflating mailbox and move on.
 * No subscriber can slow the tick generator down, and no subscriber can make the process run out
 * of memory - {@link ConflatingMailbox} bounds each mailbox by the symbol universe.
 *
 * <p>Registry is a {@link ConcurrentHashMap} because subscriptions are created and removed by HTTP
 * threads while the generator thread iterates. Iterating a {@code ConcurrentHashMap} is weakly
 * consistent, which is precisely what is wanted here: a subscription added mid-fan-out may or may
 * not see this tick, and it will certainly see the next one 100 milliseconds later. Paying for a
 * consistent iteration - a lock, or a copy per tick - would buy nothing.
 */
@Component
public class TickBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TickBroadcaster.class);

    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public TickBroadcaster(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        Gauge.builder("oms.marketdata.subscribers", subscriptions, Map::size)
                .description("Open streaming subscriptions on this instance")
                .register(meterRegistry);

        // Total conflation across all subscribers. A rising value means clients cannot keep up -
        // which is a fact about the clients, not an error, and worth being able to see.
        Gauge.builder("oms.marketdata.conflated.total", this, TickBroadcaster::totalConflated)
                .description("Ticks that superseded an undelivered tick")
                .register(meterRegistry);
    }

    public Subscription register(Subscription subscription) {
        subscriptions.put(subscription.id(), subscription);
        log.info("Subscription {} opened for {} ({} total)", subscription.id(),
                subscription.symbols().isEmpty() ? "ALL symbols" : subscription.symbols(),
                subscriptions.size());
        return subscription;
    }

    public void unregister(Subscription subscription) {
        subscriptions.remove(subscription.id());
        subscription.close();
        log.info("Subscription {} closed after {} ticks delivered, {} conflated ({} remaining)",
                subscription.id(),
                subscription.mailbox().deliveredCount(),
                subscription.mailbox().conflatedCount(),
                subscriptions.size());
    }

    /**
     * Offers a tick to every interested subscription. Called from the tick generator thread.
     *
     * @return the number of subscriptions the tick was offered to
     */
    public int publish(MarketTickEvent tick) {
        int offered = 0;
        for (Subscription subscription : subscriptions.values()) {
            if (!subscription.isOpen()) {
                // Cleaned up lazily here as well as on close: an HTTP thread that died without
                // running its completion callback would otherwise leave an entry behind for ever.
                subscriptions.remove(subscription.id());
                continue;
            }
            if (subscription.offer(tick)) {
                offered++;
            }
        }
        return offered;
    }

    public int subscriberCount() {
        return subscriptions.size();
    }

    public Collection<Subscription> subscriptions() {
        return subscriptions.values();
    }

    private double totalConflated() {
        long total = 0;
        for (Subscription subscription : subscriptions.values()) {
            total += subscription.mailbox().conflatedCount();
        }
        return total;
    }
}
