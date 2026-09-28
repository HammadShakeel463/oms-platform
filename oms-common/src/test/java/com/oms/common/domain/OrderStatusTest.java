package com.oms.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    @DisplayName("happy path walks NEW -> VALIDATED -> ROUTED -> PARTIALLY_FILLED -> FILLED")
    void happyPathIsLegal() {
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.VALIDATED)).isTrue();
        assertThat(OrderStatus.VALIDATED.canTransitionTo(OrderStatus.ROUTED)).isTrue();
        assertThat(OrderStatus.ROUTED.canTransitionTo(OrderStatus.PARTIALLY_FILLED)).isTrue();
        assertThat(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.FILLED)).isTrue();
    }

    @Test
    @DisplayName("repeated partial fills stay in PARTIALLY_FILLED")
    void partialFillIsSelfTransitioning() {
        assertThat(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.PARTIALLY_FILLED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"FILLED", "CANCELLED", "REJECTED"})
    @DisplayName("terminal states accept nothing further")
    void terminalStatesAreClosed(OrderStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.isLive()).isFalse();
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    @DisplayName("a filled order cannot be resurrected or cancelled")
    void noBackwardsTransitions() {
        assertThat(OrderStatus.FILLED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.FILLED.canTransitionTo(OrderStatus.PARTIALLY_FILLED)).isFalse();
        assertThat(OrderStatus.ROUTED.canTransitionTo(OrderStatus.NEW)).isFalse();
        assertThat(OrderStatus.ROUTED.canTransitionTo(OrderStatus.VALIDATED)).isFalse();
    }

    @Test
    @DisplayName("only ROUTED and PARTIALLY_FILLED can receive fills")
    void liveStates() {
        assertThat(OrderStatus.ROUTED.isLive()).isTrue();
        assertThat(OrderStatus.PARTIALLY_FILLED.isLive()).isTrue();
        assertThat(OrderStatus.NEW.isLive()).isFalse();
        assertThat(OrderStatus.VALIDATED.isLive()).isFalse();
    }

    @Test
    @DisplayName("allowedTargets is unmodifiable, so the table cannot be mutated at runtime")
    void transitionTableIsImmutable() {
        var targets = OrderStatus.NEW.allowedTargets();
        assertThat(targets).isNotEmpty();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> targets.add(OrderStatus.FILLED))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
