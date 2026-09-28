package com.oms.common.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order lifecycle states and the only legal transitions between them.
 *
 * <p>The transition table lives here, in the shared contract module, so that
 * order-service (which owns the write side) and every consumer of the lifecycle
 * topic agree on what a legal history looks like. A rejected transition is a bug
 * or a replay, never a business outcome.
 *
 * <pre>
 *   NEW -> VALIDATED -> ROUTED -> PARTIALLY_FILLED -> FILLED
 *    |         |          |            |
 *    |         |          +------------+--> CANCELLED
 *    +---------+----------------------------> REJECTED
 * </pre>
 */
public enum OrderStatus {

    /** Accepted by the API, persisted, nothing else has happened yet. */
    NEW,

    /** Passed bean validation, reference-data checks and pre-trade risk. */
    VALIDATED,

    /** Published to the matching engine; the engine now owns execution. */
    ROUTED,

    /** At least one fill, residual quantity still live. */
    PARTIALLY_FILLED,

    /** Fully executed. Terminal. */
    FILLED,

    /** Cancelled before full execution; residual quantity released. Terminal. */
    CANCELLED,

    /** Refused by validation, reference data or risk. Terminal. */
    REJECTED;

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS;

    static {
        EnumMap<OrderStatus, Set<OrderStatus>> t = new EnumMap<>(OrderStatus.class);
        // Collections.unmodifiableSet on each value as well as on the map: an unmodifiable
        // map of mutable sets is still mutable, and this table is shared static state.
        t.put(NEW, frozen(EnumSet.of(VALIDATED, REJECTED, CANCELLED)));
        t.put(VALIDATED, frozen(EnumSet.of(ROUTED, REJECTED, CANCELLED)));
        t.put(ROUTED, frozen(EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED)));
        t.put(PARTIALLY_FILLED, frozen(EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED)));
        t.put(FILLED, frozen(EnumSet.noneOf(OrderStatus.class)));
        t.put(CANCELLED, frozen(EnumSet.noneOf(OrderStatus.class)));
        t.put(REJECTED, frozen(EnumSet.noneOf(OrderStatus.class)));
        TRANSITIONS = Collections.unmodifiableMap(t);
    }

    private static Set<OrderStatus> frozen(Set<OrderStatus> set) {
        return Collections.unmodifiableSet(set);
    }

    /** True for states that can never change again. */
    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    /** True while the order can still receive fills. */
    public boolean isLive() {
        return this == ROUTED || this == PARTIALLY_FILLED;
    }

    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public Set<OrderStatus> allowedTargets() {
        return TRANSITIONS.get(this);
    }
}
