package com.oms.marketdata.stream;

import com.oms.common.event.MarketTickEvent;

import java.util.Set;
import java.util.UUID;

/**
 * One streaming client: its symbol filter, its conflating mailbox, and a flag for whether it is
 * still alive.
 */
public final class Subscription {

    private final String id = UUID.randomUUID().toString();
    private final Set<String> symbols;
    private final ConflatingMailbox mailbox = new ConflatingMailbox();
    private volatile boolean open = true;

    /**
     * @param symbols the symbols this client asked for; an empty set means everything
     */
    public Subscription(Set<String> symbols) {
        this.symbols = Set.copyOf(symbols);
    }

    public String id() {
        return id;
    }

    public boolean wants(String symbol) {
        return symbols.isEmpty() || symbols.contains(symbol);
    }

    public Set<String> symbols() {
        return symbols;
    }

    public ConflatingMailbox mailbox() {
        return mailbox;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Marks the subscription dead. Read by the publisher on every tick and by the delivery loop,
     * so it is {@code volatile}: the closing thread is the HTTP container's, not the one that
     * reads it.
     */
    public void close() {
        this.open = false;
        mailbox.clear();
    }

    public boolean offer(MarketTickEvent tick) {
        if (!open || !wants(tick.symbol())) {
            return false;
        }
        mailbox.offer(tick);
        return true;
    }
}
