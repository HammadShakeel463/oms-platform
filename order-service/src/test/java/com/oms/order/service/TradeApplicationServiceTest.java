package com.oms.order.service;

import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.Side;
import com.oms.common.event.CancelReason;
import com.oms.common.event.OrderCancelConfirmedEvent;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.order.TestFixtures;
import com.oms.order.domain.Liquidity;
import com.oms.order.domain.OrderEntity;
import com.oms.order.domain.OrderFillEntity;
import com.oms.order.domain.ProcessedEvent;
import com.oms.order.repository.OrderFillRepository;
import com.oms.order.repository.OrderRepository;
import com.oms.order.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradeApplicationServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderFillRepository fillRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private OrderLifecycleService lifecycle;
    @Mock
    private ExposureService exposureService;

    private TradeApplicationService service;

    private OrderEntity buyOrder;
    private OrderEntity sellOrder;

    @BeforeEach
    void setUp() {
        service = new TradeApplicationService(orderRepository, fillRepository,
                processedEventRepository, lifecycle, exposureService, new SimpleMeterRegistry());

        buyOrder = routed(TestFixtures.limitOrder(Side.BUY, 1_000, "173.0000"));
        sellOrder = routed(TestFixtures.limitOrder(Side.SELL, 1_000, "172.0000"));

        when(orderRepository.findById(buyOrder.getOrderId())).thenReturn(Optional.of(buyOrder));
        when(orderRepository.findById(sellOrder.getOrderId())).thenReturn(Optional.of(sellOrder));
        when(fillRepository.existsById(any())).thenReturn(false);
        when(processedEventRepository.existsById(any())).thenReturn(false);
    }

    private static OrderEntity routed(OrderEntity order) {
        order.transitionTo(OrderStatus.VALIDATED, null);
        order.transitionTo(OrderStatus.ROUTED, null);
        return order;
    }

    @Test
    @DisplayName("a partial fill updates both sides and leaves them PARTIALLY_FILLED")
    void partialFillAppliesToBothSides() {
        TradeExecutedEvent trade = TestFixtures.trade(
                buyOrder.getOrderId(), sellOrder.getOrderId(), "172.5000", 400, Side.BUY);

        service.applyTrade(trade);

        assertThat(buyOrder.getFilledQuantity()).isEqualTo(400);
        assertThat(sellOrder.getFilledQuantity()).isEqualTo(400);
        verify(lifecycle).transition(eq(buyOrder), eq(OrderStatus.PARTIALLY_FILLED), anyString(), anyString());
        verify(lifecycle).transition(eq(sellOrder), eq(OrderStatus.PARTIALLY_FILLED), anyString(), anyString());
        verify(exposureService).fillApplied(anyString(), anyString(), eq(Side.BUY), eq(400L));
        verify(exposureService).fillApplied(anyString(), anyString(), eq(Side.SELL), eq(400L));
    }

    @Test
    @DisplayName("a fill that completes the order moves it to FILLED")
    void completingFillMovesToFilled() {
        service.applyTrade(TestFixtures.trade(
                buyOrder.getOrderId(), sellOrder.getOrderId(), "172.5000", 1_000, Side.BUY));

        verify(lifecycle).transition(eq(buyOrder), eq(OrderStatus.FILLED), anyString(), anyString());
        assertThat(buyOrder.isFullyFilled()).isTrue();
    }

    @Test
    @DisplayName("the aggressor is the taker and the resting side is the maker")
    void liquidityFlagsFollowTheAggressor() {
        service.applyTrade(TestFixtures.trade(
                buyOrder.getOrderId(), sellOrder.getOrderId(), "172.5000", 100, Side.BUY));

        ArgumentCaptor<OrderFillEntity> captor = ArgumentCaptor.forClass(OrderFillEntity.class);
        verify(fillRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        var fills = captor.getAllValues();
        assertThat(fills).extracting(OrderFillEntity::getLiquidity)
                .containsExactly(Liquidity.TAKER, Liquidity.MAKER);
        assertThat(fills.get(0).getOrderId()).isEqualTo(buyOrder.getOrderId());
    }

    @Test
    @DisplayName("a replayed trade is ignored - this is what makes at-least-once safe")
    void replayedTradeIsIgnored() {
        when(fillRepository.existsById(any())).thenReturn(true);

        service.applyTrade(TestFixtures.trade(
                buyOrder.getOrderId(), sellOrder.getOrderId(), "172.5000", 400, Side.BUY));

        assertThat(buyOrder.getFilledQuantity()).isZero();
        verify(fillRepository, never()).save(any());
        verify(lifecycle, never()).transition(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a fill for an unknown order is alerted on, not dead-lettered")
    void unknownOrderDoesNotFailTheWholeTrade() {
        UUID unknown = UUID.randomUUID();
        when(orderRepository.findById(unknown)).thenReturn(Optional.empty());

        service.applyTrade(TestFixtures.trade(
                unknown, sellOrder.getOrderId(), "172.5000", 400, Side.BUY));

        // The good side still applied: dead-lettering the record would have discarded it.
        assertThat(sellOrder.getFilledQuantity()).isEqualTo(400);
    }

    @Test
    @DisplayName("a fill against an already-terminal order is refused rather than overfilling it")
    void fillOnDeadOrderIsRefused() {
        buyOrder.transitionTo(OrderStatus.CANCELLED, "cancelled first");

        service.applyTrade(TestFixtures.trade(
                buyOrder.getOrderId(), sellOrder.getOrderId(), "172.5000", 400, Side.BUY));

        assertThat(buyOrder.getFilledQuantity()).isZero();
        assertThat(sellOrder.getFilledQuantity()).isEqualTo(400);
    }

    @Test
    @DisplayName("a cancel confirmation moves the order to CANCELLED and releases working quantity")
    void cancelConfirmationCancelsAndReleases() {
        buyOrder.applyFill(new java.math.BigDecimal("172.5000"), 300);

        service.applyCancelConfirmation(confirmation(buyOrder.getOrderId(), CancelReason.USER_REQUEST));

        verify(lifecycle).transition(eq(buyOrder), eq(OrderStatus.CANCELLED),
                eq("cancelled by engine: USER_REQUEST"), anyString());
        verify(exposureService).workingReleased(anyString(), anyString(), eq(Side.BUY), eq(700L));
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a replayed cancel confirmation is a no-op")
    void replayedCancelConfirmationIsIgnored() {
        when(processedEventRepository.existsById(any())).thenReturn(true);

        service.applyCancelConfirmation(confirmation(buyOrder.getOrderId(), CancelReason.USER_REQUEST));

        verify(lifecycle, never()).transition(any(), any(), anyString(), anyString());
        verify(exposureService, never()).workingReleased(anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("UNKNOWN_ORDER from the engine does not cancel a locally live order")
    void unknownOrderReasonIsNotApplied() {
        service.applyCancelConfirmation(confirmation(buyOrder.getOrderId(), CancelReason.UNKNOWN_ORDER));

        verify(lifecycle, never()).transition(any(), any(), anyString(), anyString());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    private static OrderCancelConfirmedEvent confirmation(UUID orderId, CancelReason reason) {
        return new OrderCancelConfirmedEvent(UUID.randomUUID().toString(), Instant.now(),
                OrderCancelConfirmedEvent.CURRENT_SCHEMA_VERSION, orderId, TestFixtures.SYMBOL,
                TestFixtures.ACCOUNT, reason, 700, 99L);
    }
}
