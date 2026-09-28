package com.oms.order.domain;

import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.error.IllegalStateTransitionException;
import com.oms.order.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderEntityTest {

    @Test
    @DisplayName("an order is born NEW with no fills")
    void newOrderInvariants() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(order.getFilledQuantity()).isZero();
        assertThat(order.leavesQuantity()).isEqualTo(1_000);
        assertThat(order.getOrderId()).isNotNull();
        assertThat(order.isFullyFilled()).isFalse();
    }

    @Test
    @DisplayName("a LIMIT order without a price cannot be constructed")
    void limitRequiresPrice() {
        assertThatThrownBy(() -> OrderEntity.newOrder("cl-1", "ACC-1", "HBL", Side.BUY,
                OrderType.LIMIT, TimeInForce.DAY, null, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIMIT order requires a limit price");
    }

    @Test
    @DisplayName("a MARKET order with a price cannot be constructed")
    void marketRejectsPrice() {
        assertThatThrownBy(() -> OrderEntity.newOrder("cl-1", "ACC-1", "HBL", Side.BUY,
                OrderType.MARKET, TimeInForce.IOC, new BigDecimal("172.45"), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry a limit price");
    }

    @Test
    @DisplayName("partial fills roll the volume-weighted average price forward")
    void averagePriceIsVolumeWeighted() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 1_000, "173.0000");

        order.applyFill(new BigDecimal("172.0000"), 400);
        assertThat(order.getAvgPrice()).isEqualByComparingTo("172.0000");

        order.applyFill(new BigDecimal("173.0000"), 600);
        // (400 x 172 + 600 x 173) / 1000 = 172.6
        assertThat(order.getAvgPrice()).isEqualByComparingTo("172.6000");
        assertThat(order.isFullyFilled()).isTrue();
        assertThat(order.leavesQuantity()).isZero();
    }

    @Test
    @DisplayName("an overfill is refused rather than recorded")
    void overfillIsRefused() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 100, "172.4500");
        order.applyFill(new BigDecimal("172.4500"), 60);

        assertThatThrownBy(() -> order.applyFill(new BigDecimal("172.4500"), 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overfill");

        assertThat(order.getFilledQuantity()).isEqualTo(60);
    }

    @Test
    @DisplayName("an illegal transition throws instead of being applied")
    void illegalTransitionIsRefused() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 100, "172.4500");
        order.transitionTo(OrderStatus.VALIDATED, null);
        order.transitionTo(OrderStatus.ROUTED, null);
        order.transitionTo(OrderStatus.FILLED, null);

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.CANCELLED, "too late"))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    @DisplayName("signed quantity carries the side")
    void signedQuantity() {
        assertThat(TestFixtures.limitOrder(Side.BUY, 500, "172.4500").signedQuantity())
                .isEqualTo(500);
        assertThat(TestFixtures.limitOrder(Side.SELL, 500, "172.4500").signedQuantity())
                .isEqualTo(-500);
    }

    @Test
    @DisplayName("equality is by id, so a reloaded copy is the same order")
    void identityEquality() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 100, "172.4500");

        assertThat(order).isEqualTo(order);
        assertThat(order).isNotEqualTo(TestFixtures.limitOrder(Side.BUY, 100, "172.4500"));
        assertThat(order).isNotEqualTo(null);
    }
}
