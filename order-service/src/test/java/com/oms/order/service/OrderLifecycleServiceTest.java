package com.oms.order.service;

import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.Side;
import com.oms.common.event.OrderLifecycleEvent;
import com.oms.common.event.Topics;
import com.oms.common.money.Ticks;
import com.oms.order.TestFixtures;
import com.oms.order.domain.OrderAuditEntity;
import com.oms.order.domain.OrderEntity;
import com.oms.order.messaging.OutboxWriter;
import com.oms.order.repository.OrderAuditRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The audit trail and the lifecycle event, which are produced together and must agree.
 *
 * <p>The audit row is the source of truth and the order row is a derived cache of its latest
 * state, so "an audit row was written" and "an event was published saying the same thing" are one
 * operation. A transition that updated the order and skipped either output would leave the
 * platform unable to answer why an order is in the state it is in - which is the question a
 * trading system exists to be able to answer.
 */
@ExtendWith(MockitoExtension.class)
class OrderLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T09:15:00Z");

    @Mock
    private OrderAuditRepository auditRepository;
    @Mock
    private OutboxWriter outboxWriter;

    @Captor
    private ArgumentCaptor<OrderLifecycleEvent> published;
    @Captor
    private ArgumentCaptor<OrderAuditEntity> saved;

    private MeterRegistry meterRegistry;
    private OrderLifecycleService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new OrderLifecycleService(auditRepository, outboxWriter, meterRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC));
        // lenient: the illegal-transition test must reach no repository call at all, and a
        // strict stub declared here would be reported as unused by exactly that test.
        lenient().when(auditRepository.save(saved.capture())).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("creation writes audit seq 1 with no previous status, and a matching NEW event")
    void creationIsSequenceOne() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");

        service.recordCreation(order, "trader1");

        OrderAuditEntity audit = saved.getValue();
        assertThat(audit.getSeq()).isEqualTo(1);
        assertThat(audit.getPreviousStatus())
                .as("there is no state before the first one; null is the honest value")
                .isNull();
        assertThat(audit.getNewStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(audit.getFilledQuantity()).isZero();
        assertThat(audit.getLeavesQuantity()).isEqualTo(1_000);
        assertThat(audit.getActor()).isEqualTo("trader1");
        assertThat(audit.getOccurredAt()).isEqualTo(NOW);

        verify(outboxWriter).enqueue(eq(Topics.ORDERS_LIFECYCLE), published.capture(),
                eq(order.getOrderId().toString()));
        OrderLifecycleEvent event = published.getValue();
        assertThat(event.previousStatus()).isNull();
        assertThat(event.newStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(event.leavesQuantity()).isEqualTo(1_000);
        assertThat(event.averagePriceTicks())
                .as("nothing has traded, so there is no average price - not a zero")
                .isEqualTo(Ticks.NO_PRICE);
        assertThat(event.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("the event goes to the outbox keyed by order id, never straight to Kafka")
    void eventIsKeyedByOrderId() {
        OrderEntity order = TestFixtures.limitOrder(Side.SELL, 500, "172.5000");

        service.recordCreation(order, "trader1");

        verify(outboxWriter).enqueue(eq(Topics.ORDERS_LIFECYCLE), any(OrderLifecycleEvent.class),
                eq(order.getOrderId().toString()));
    }

    @Test
    @DisplayName("a transition advances the audit sequence from whatever is already stored")
    void transitionContinuesTheSequence() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");
        when(auditRepository.currentSeq(order.getOrderId())).thenReturn(4);

        service.transition(order, OrderStatus.VALIDATED, "risk checks passed", "system");

        assertThat(saved.getValue().getSeq())
                .as("the sequence has to come from the stored trail, not from a counter in memory")
                .isEqualTo(5);
        verify(outboxWriter).enqueue(eq(Topics.ORDERS_LIFECYCLE), published.capture(), anyString());
        assertThat(published.getValue().version()).isEqualTo(5);
    }

    @Test
    @DisplayName("the audit row and the event agree on both ends of the transition")
    void auditAndEventAgree() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");
        order.transitionTo(OrderStatus.VALIDATED, null);
        when(auditRepository.currentSeq(order.getOrderId())).thenReturn(2);

        service.transition(order, OrderStatus.ROUTED, "sent to engine", "system");

        verify(outboxWriter).enqueue(eq(Topics.ORDERS_LIFECYCLE), published.capture(), anyString());
        OrderAuditEntity audit = saved.getValue();
        OrderLifecycleEvent event = published.getValue();

        assertThat(audit.getPreviousStatus()).isEqualTo(OrderStatus.VALIDATED);
        assertThat(audit.getNewStatus()).isEqualTo(OrderStatus.ROUTED);
        assertThat(event.previousStatus()).isEqualTo(audit.getPreviousStatus());
        assertThat(event.newStatus()).isEqualTo(audit.getNewStatus());
        assertThat(event.reason()).isEqualTo(audit.getReason());
        assertThat(order.getStatus())
                .as("the order row is the derived cache of the latest audit row")
                .isEqualTo(OrderStatus.ROUTED);
    }

    @Test
    @DisplayName("a rejection reason is stored on the order; other reasons are audit-only")
    void onlyRejectionStampsTheOrder() {
        OrderEntity rejected = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");
        service.transition(rejected, OrderStatus.REJECTED, "MAX_ORDER_VALUE", "system");
        assertThat(rejected.getRejectReason()).isEqualTo("MAX_ORDER_VALUE");

        OrderEntity validated = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");
        service.transition(validated, OrderStatus.VALIDATED, "risk checks passed", "system");
        assertThat(validated.getRejectReason())
                .as("a reject reason set on a non-rejected order would be read as a rejection")
                .isNull();
        assertThat(saved.getValue().getReason())
                .as("the audit row still records why, for every transition")
                .isEqualTo("risk checks passed");
    }

    @Test
    @DisplayName("an illegal transition is refused before anything is written")
    void illegalTransitionWritesNothing() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");
        order.transitionTo(OrderStatus.VALIDATED, null);
        order.transitionTo(OrderStatus.ROUTED, null);
        order.transitionTo(OrderStatus.CANCELLED, null);

        assertThatThrownBy(() -> service.transition(order, OrderStatus.FILLED, "late fill", "system"))
                .isInstanceOf(RuntimeException.class);

        verify(auditRepository, never()).save(any());
        verify(outboxWriter, never()).enqueue(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("the trace id is carried onto the audit row, so a row maps back to a request")
    void traceIdIsRecorded() {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");

        service.recordCreation(order, "trader1");

        assertThat(saved.getValue().getTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("transitions are counted by from/to, which is the state machine as a metric")
    void transitionsAreCountedByEdge() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");

        service.transition(order, OrderStatus.VALIDATED, "ok", "system");

        assertThat(meterRegistry.counter("oms.order.transitions",
                "from", "NEW", "to", "VALIDATED").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("creation is counted per symbol")
    void creationIsCountedPerSymbol() {
        service.recordCreation(TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500"), "trader1");

        assertThat(meterRegistry.counter("oms.order.created", "symbol", TestFixtures.SYMBOL).count())
                .isEqualTo(1.0);
    }
}
