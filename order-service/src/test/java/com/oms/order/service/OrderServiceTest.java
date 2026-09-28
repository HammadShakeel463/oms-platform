package com.oms.order.service;

import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.error.ConflictException;
import com.oms.common.error.NotFoundException;
import com.oms.common.error.RiskRejectedException;
import com.oms.common.event.OrderAcceptedEvent;
import com.oms.common.event.OrderCancelRequestedEvent;
import com.oms.common.event.Topics;
import com.oms.common.money.Ticks;
import com.oms.order.TestFixtures;
import com.oms.order.config.OrderProperties;
import com.oms.order.domain.OrderEntity;
import com.oms.order.messaging.OutboxWriter;
import com.oms.order.reference.InstrumentClient;
import com.oms.order.repository.AccountRepository;
import com.oms.order.repository.OrderAuditRepository;
import com.oms.order.repository.OrderRepository;
import com.oms.order.risk.RiskContext;
import com.oms.order.risk.RiskEngine;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-layer unit tests.
 *
 * <p>Mockito replaces the collaborators the service talks to, so these run with no
 * database, no broker and no Spring context. What is being tested is the orchestration:
 * the order of operations, which collaborator is called with what, and what happens when
 * one of them refuses.
 *
 * <p>Note what is <em>not</em> mocked: {@code MeterRegistry} (a real
 * {@code SimpleMeterRegistry} is simpler than stubbing a fluent API) and {@code Clock}
 * (fixed, so timestamps are assertable). Mock what you need to control or verify; use the
 * real thing when it is cheap and deterministic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T09:15:00Z");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderAuditRepository auditRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private InstrumentClient instrumentClient;
    @Mock
    private RiskEngine riskEngine;
    @Mock
    private OrderLifecycleService lifecycle;
    @Mock
    private ExposureService exposureService;
    @Mock
    private OutboxWriter outboxWriter;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, auditRepository, accountRepository,
                instrumentClient, riskEngine, lifecycle, exposureService, outboxWriter,
                new OrderProperties(new BigDecimal("0.05"), 200, java.time.Duration.ofDays(3)),
                new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PlaceOrderCommand command() {
        return new PlaceOrderCommand(TestFixtures.ACCOUNT, "cl-001", TestFixtures.SYMBOL,
                Side.BUY, OrderType.LIMIT, TimeInForce.DAY, new BigDecimal("172.4500"),
                1_000, "user-1");
    }

    private void givenHappyDependencies() {
        when(accountRepository.findById(TestFixtures.ACCOUNT))
                .thenReturn(Optional.of(TestFixtures.account()));
        when(orderRepository.existsByAccountIdAndClientOrderId(anyString(), anyString()))
                .thenReturn(false);
        when(instrumentClient.findBySymbol(TestFixtures.SYMBOL))
                .thenReturn(TestFixtures.instrument());
        when(exposureService.load(anyString(), anyString())).thenReturn(TestFixtures.exposure());
    }

    @Test
    @DisplayName("a valid order is persisted, journalled, risk-checked and routed")
    void placesOrder() {
        givenHappyDependencies();

        OrderEntity order = orderService.placeOrder(command());

        assertThat(order.getSymbol()).isEqualTo(TestFixtures.SYMBOL);
        assertThat(order.getQuantity()).isEqualTo(1_000);

        verify(orderRepository).save(order);
        verify(lifecycle).recordCreation(order, "user-1");
        verify(riskEngine).evaluate(any(RiskContext.class));
        verify(lifecycle).transition(order, OrderStatus.VALIDATED, "passed pre-trade risk", "user-1");
        verify(lifecycle).transition(order, OrderStatus.ROUTED, "sent to matching engine", "user-1");
        verify(exposureService).orderWentLive(any(), eq(Side.BUY), eq(1_000L));
    }

    @Test
    @DisplayName("the event handed to the matching engine carries the order in fixed-point ticks")
    void publishesAcceptedEventToTheOutbox() {
        givenHappyDependencies();

        OrderEntity order = orderService.placeOrder(command());

        var captor = ArgumentCaptor.forClass(OrderAcceptedEvent.class);
        verify(outboxWriter).enqueue(eq(Topics.ORDERS_ACCEPTED), captor.capture(),
                eq(order.getOrderId().toString()));

        OrderAcceptedEvent event = captor.getValue();
        assertThat(event.orderId()).isEqualTo(order.getOrderId());
        assertThat(event.limitPriceTicks()).isEqualTo(1_724_500L);
        assertThat(event.quantity()).isEqualTo(1_000);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.partitionKey()).isEqualTo(TestFixtures.SYMBOL);
    }

    @Test
    @DisplayName("a MARKET order is priced for risk at the reference price plus slippage")
    void marketOrderIsPricedPessimistically() {
        givenHappyDependencies();

        orderService.placeOrder(new PlaceOrderCommand(TestFixtures.ACCOUNT, "cl-mkt",
                TestFixtures.SYMBOL, Side.BUY, OrderType.MARKET, TimeInForce.IOC,
                null, 100, "user-1"));

        var captor = ArgumentCaptor.forClass(RiskContext.class);
        verify(riskEngine).evaluate(captor.capture());

        // 1,724,500 x 1.05 = 1,810,725
        assertThat(captor.getValue().effectivePriceTicks()).isEqualTo(1_810_725L);

        // ... but the event sent to the engine carries NO_PRICE: the slippage assumption is
        // a risk input, not an instruction to the book.
        var eventCaptor = ArgumentCaptor.forClass(OrderAcceptedEvent.class);
        verify(outboxWriter).enqueue(eq(Topics.ORDERS_ACCEPTED), eventCaptor.capture(), anyString());
        assertThat(eventCaptor.getValue().limitPriceTicks()).isEqualTo(Ticks.NO_PRICE);
    }

    @Test
    @DisplayName("an unknown account is a 404, before anything is written")
    void unknownAccount() {
        when(accountRepository.findById(TestFixtures.ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.placeOrder(command()))
                .isInstanceOf(NotFoundException.class);

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("a reused clientOrderId is a 409")
    void duplicateClientOrderId() {
        when(accountRepository.findById(TestFixtures.ACCOUNT))
                .thenReturn(Optional.of(TestFixtures.account()));
        when(orderRepository.existsByAccountIdAndClientOrderId(TestFixtures.ACCOUNT, "cl-001"))
                .thenReturn(true);

        assertThatThrownBy(() -> orderService.placeOrder(command()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cl-001");

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("a risk rejection is recorded as REJECTED and then rethrown")
    void riskRejectionIsRecordedNotSwallowed() {
        givenHappyDependencies();
        doThrow(new RiskRejectedException("MAX_POSITION", "too big"))
                .when(riskEngine).evaluate(any(RiskContext.class));

        assertThatThrownBy(() -> orderService.placeOrder(command()))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("too big");

        // The order exists and its history says why it was refused. That is the whole
        // reason placeOrder declares noRollbackFor = RiskRejectedException.
        verify(lifecycle).transition(any(OrderEntity.class), eq(OrderStatus.REJECTED),
                eq("MAX_POSITION: too big"), eq("user-1"));
        verify(lifecycle, never()).transition(any(), eq(OrderStatus.VALIDATED), any(), any());
        verify(outboxWriter, never()).enqueue(eq(Topics.ORDERS_ACCEPTED), any(), anyString());
    }

    @Test
    @DisplayName("cancelling requests a cancel and does NOT mark the order cancelled")
    void cancelIsARequestNotAnOutcome() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");
        order.transitionTo(OrderStatus.VALIDATED, null);
        order.transitionTo(OrderStatus.ROUTED, null);

        when(orderRepository.findByOrderIdAndAccountId(order.getOrderId(), TestFixtures.ACCOUNT))
                .thenReturn(Optional.of(order));

        OrderEntity result = orderService.requestCancel(
                order.getOrderId(), TestFixtures.ACCOUNT, "user-1");

        assertThat(result.getStatus())
                .as("the engine owns the book, so only it can confirm a cancel")
                .isEqualTo(OrderStatus.ROUTED);

        var captor = ArgumentCaptor.forClass(OrderCancelRequestedEvent.class);
        verify(outboxWriter).enqueue(eq(Topics.ORDERS_CANCEL_REQUESTS), captor.capture(), anyString());
        assertThat(captor.getValue().orderId()).isEqualTo(order.getOrderId());
        assertThat(captor.getValue().partitionKey()).isEqualTo(TestFixtures.SYMBOL);
    }

    @Test
    @DisplayName("cancelling a filled order is a 409, and nothing is published")
    void cannotCancelATerminalOrder() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");
        order.transitionTo(OrderStatus.VALIDATED, null);
        order.transitionTo(OrderStatus.ROUTED, null);
        order.transitionTo(OrderStatus.FILLED, null);

        when(orderRepository.findByOrderIdAndAccountId(order.getOrderId(), TestFixtures.ACCOUNT))
                .thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.requestCancel(
                order.getOrderId(), TestFixtures.ACCOUNT, "user-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("FILLED");

        verify(outboxWriter, never()).enqueue(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("an order belonging to another account is a 404, not a 403")
    void crossAccountAccessIsNotFound() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByOrderIdAndAccountId(orderId, "ACC-OTHER"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findOrder(orderId, "ACC-OTHER"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("the order is saved before its creation is journalled")
    void savesBeforeJournalling() {
        givenHappyDependencies();
        // The audit row has a foreign key to orders, so the insert order is not cosmetic.
        doAnswer(invocation -> {
            verify(orderRepository).save(invocation.getArgument(0, OrderEntity.class));
            return null;
        }).when(lifecycle).recordCreation(any(), anyString());

        orderService.placeOrder(command());
    }
}
