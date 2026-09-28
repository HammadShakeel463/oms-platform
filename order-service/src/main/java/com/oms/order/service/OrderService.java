package com.oms.order.service;

import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.OrderType;
import com.oms.common.error.ConflictException;
import com.oms.common.error.ErrorCode;
import com.oms.common.error.NotFoundException;
import com.oms.common.error.RiskRejectedException;
import com.oms.common.event.OrderAcceptedEvent;
import com.oms.common.event.OrderCancelRequestedEvent;
import com.oms.common.event.Topics;
import com.oms.common.money.Ticks;
import com.oms.common.reference.InstrumentView;
import com.oms.order.config.OrderProperties;
import com.oms.order.domain.AccountEntity;
import com.oms.order.domain.OrderAuditEntity;
import com.oms.order.domain.OrderEntity;
import com.oms.order.domain.PositionExposureEntity;
import com.oms.order.messaging.OutboxWriter;
import com.oms.order.reference.InstrumentClient;
import com.oms.order.repository.AccountRepository;
import com.oms.order.repository.OrderAuditRepository;
import com.oms.order.repository.OrderRepository;
import com.oms.order.risk.RiskContext;
import com.oms.order.risk.RiskEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The write side of the order lifecycle, and the transaction boundary for it.
 *
 * <p><b>Where {@code @Transactional} goes.</b> On the public service methods, and nowhere
 * else. Not on the controller (a transaction spanning HTTP serialisation holds a database
 * connection while writing bytes to a socket), and not on the repositories (each would then
 * be its own transaction and a multi-step operation could half-commit). One annotated entry
 * point per use case means the boundary is visible in one place.
 *
 * <p><b>How it actually works, because it matters.</b> {@code @Transactional} is a
 * proxy-based aspect: Spring wraps this bean in a proxy that opens a transaction before the
 * method and commits or rolls back after. Three consequences that catch people out:
 * a self-call from one method of this class to another bypasses the proxy entirely and runs
 * with no new transaction; only {@code public} methods are advised; and the default rollback
 * rule is unchecked exceptions only.
 *
 * <p>For a C++ reader the nearest analogue is a scope guard: the proxy is an RAII wrapper
 * you did not write, around a method you did. The important difference is that the guard is
 * installed by the container at the call site, so calling the method a different way -
 * internally, or on {@code this} - silently removes it.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final String ACTOR_API = "api";

    private final OrderRepository orderRepository;
    private final OrderAuditRepository auditRepository;
    private final AccountRepository accountRepository;
    private final InstrumentClient instrumentClient;
    private final RiskEngine riskEngine;
    private final OrderLifecycleService lifecycle;
    private final ExposureService exposureService;
    private final OutboxWriter outboxWriter;
    private final OrderProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository,
                        OrderAuditRepository auditRepository,
                        AccountRepository accountRepository,
                        InstrumentClient instrumentClient,
                        RiskEngine riskEngine,
                        OrderLifecycleService lifecycle,
                        ExposureService exposureService,
                        OutboxWriter outboxWriter,
                        OrderProperties properties,
                        MeterRegistry meterRegistry,
                        Clock clock) {
        this.orderRepository = orderRepository;
        this.auditRepository = auditRepository;
        this.accountRepository = accountRepository;
        this.instrumentClient = instrumentClient;
        this.riskEngine = riskEngine;
        this.lifecycle = lifecycle;
        this.exposureService = exposureService;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // =================================================================================
    //  Placement
    // =================================================================================

    /**
     * Validates, risk-checks, persists and routes an order.
     *
     * <p><b>{@code noRollbackFor = RiskRejectedException.class}</b> is the subtle part. A
     * risk rejection must do two contradictory-looking things: return HTTP 422 to the
     * caller, and leave a permanent record that the order existed and why it was refused.
     * The default rollback-on-any-RuntimeException would discard the REJECTED order and its
     * audit row along with the exception - the caller would get a clean error and the
     * compliance trail would be empty. Declaring the exception non-rollback commits the
     * rejection and still lets the exception reach the controller advice.
     *
     * <p>A rejected order is a business outcome that must be recorded, not a failure to
     * undo. That distinction is the whole reason this attribute exists.
     */
    @Transactional(noRollbackFor = RiskRejectedException.class)
    public OrderEntity placeOrder(PlaceOrderCommand cmd) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            AccountEntity account = accountRepository.findById(cmd.accountId())
                    .orElseThrow(() -> NotFoundException.account(cmd.accountId()));
            if (!account.isActive()) {
                throw new ConflictException(ErrorCode.FORBIDDEN,
                        "Account " + account.getAccountId() + " is not active");
            }

            // Checked before the insert for a clean 409. The UNIQUE constraint is still the
            // real guarantee - two concurrent retries can both pass this check, and exactly
            // one of them will then hit the constraint. Check-then-act is a nicety; the
            // constraint is the correctness.
            if (orderRepository.existsByAccountIdAndClientOrderId(
                    cmd.accountId(), cmd.clientOrderId())) {
                throw ConflictException.duplicateClientOrderId(cmd.accountId(), cmd.clientOrderId());
            }

            InstrumentView instrument = instrumentClient.findBySymbol(cmd.symbol());

            OrderEntity order = OrderEntity.newOrder(
                    cmd.clientOrderId(), cmd.accountId(), cmd.symbol(), cmd.side(),
                    cmd.orderType(), cmd.timeInForce(), cmd.limitPrice(), cmd.quantity());
            orderRepository.save(order);
            lifecycle.recordCreation(order, actor(cmd.submittedBy()));

            PositionExposureEntity exposure = exposureService.load(cmd.accountId(), cmd.symbol());
            long effectivePrice = effectivePriceTicks(order, instrument);

            try {
                riskEngine.evaluate(new RiskContext(order, instrument, account, exposure, effectivePrice));
            } catch (RiskRejectedException rejected) {
                lifecycle.transition(order, OrderStatus.REJECTED,
                        rejected.check() + ": " + rejected.getMessage(), actor(cmd.submittedBy()));
                throw rejected;
            }

            lifecycle.transition(order, OrderStatus.VALIDATED, "passed pre-trade risk",
                    actor(cmd.submittedBy()));

            publishToEngine(order, effectivePrice);
            lifecycle.transition(order, OrderStatus.ROUTED, "sent to matching engine",
                    actor(cmd.submittedBy()));
            exposureService.orderWentLive(exposure, order.getSide(), order.getQuantity());

            log.info("Accepted order {} {} {} {} for account {}",
                    order.getOrderId(), order.getSide(), order.getQuantity(),
                    order.getSymbol(), order.getAccountId());
            return order;
        } finally {
            sample.stop(meterRegistry.timer("oms.order.placement"));
        }
    }

    /**
     * Appends the command the matching engine consumes.
     *
     * <p>Into the outbox, not straight to {@code KafkaTemplate}. Publishing here would be a
     * dual write: if the send succeeded and the transaction then rolled back, the engine
     * would work an order that does not exist in the database. See ADR 0004.
     */
    private void publishToEngine(OrderEntity order, long effectivePriceTicks) {
        outboxWriter.enqueue(Topics.ORDERS_ACCEPTED, new OrderAcceptedEvent(
                OutboxWriter.newEventId(),
                clock.instant(),
                OrderAcceptedEvent.CURRENT_SCHEMA_VERSION,
                order.getOrderId(),
                order.getClientOrderId(),
                order.getAccountId(),
                order.getSymbol(),
                order.getSide(),
                order.getOrderType(),
                order.getTimeInForce(),
                order.getOrderType() == OrderType.LIMIT ? order.limitPriceTicks() : Ticks.NO_PRICE,
                order.getQuantity()), order.getOrderId().toString());
    }

    /**
     * The price risk evaluates against.
     *
     * <p>A LIMIT order carries its own. A MARKET order does not, so risk uses the
     * instrument reference price widened by a slippage factor - deliberately pessimistic,
     * because the notional a market order can actually incur is unbounded by definition and
     * a risk check that assumed the reference price would understate it.
     *
     * <p>Phase 4 replaces the reference price with the live contra-side quote from the
     * cached snapshot, which is strictly better; the widening stays.
     */
    private long effectivePriceTicks(OrderEntity order, InstrumentView instrument) {
        if (order.getOrderType() == OrderType.LIMIT) {
            return order.limitPriceTicks();
        }
        BigDecimal widened = BigDecimal.valueOf(instrument.referencePriceTicks())
                .multiply(BigDecimal.ONE.add(properties.marketOrderSlippage()))
                .setScale(0, RoundingMode.CEILING);
        return widened.longValueExact();
    }

    // =================================================================================
    //  Cancellation
    // =================================================================================

    /**
     * Requests cancellation. Note what this does <em>not</em> do: it does not set the order
     * to CANCELLED.
     *
     * <p>The matching engine owns the book, so only the engine knows whether a cancel
     * actually landed - the order may be filling at the instant the request arrives.
     * Marking it cancelled here would mean telling a client their order is dead while their
     * money moves. The order stays live until an {@code OrderCancelConfirmedEvent} comes
     * back, which is why this endpoint returns 202 Accepted rather than 200 OK.
     */
    @Transactional
    public OrderEntity requestCancel(UUID orderId, String accountId, String requestedBy) {
        OrderEntity order = orderRepository.findByOrderIdAndAccountId(orderId, accountId)
                .orElseThrow(() -> NotFoundException.order(orderId));

        if (!order.getStatus().isLive()) {
            throw new ConflictException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "Order " + orderId + " is " + order.getStatus() + " and cannot be cancelled");
        }

        outboxWriter.enqueue(Topics.ORDERS_CANCEL_REQUESTS, new OrderCancelRequestedEvent(
                OutboxWriter.newEventId(),
                clock.instant(),
                OrderCancelRequestedEvent.CURRENT_SCHEMA_VERSION,
                order.getOrderId(),
                order.getSymbol(),
                order.getAccountId(),
                actor(requestedBy)), order.getOrderId().toString());

        meterRegistry.counter("oms.order.cancel.requested", "symbol", order.getSymbol()).increment();
        log.info("Cancel requested for order {} by {}", orderId, requestedBy);
        return order;
    }

    // =================================================================================
    //  Queries
    // =================================================================================

    /**
     * {@code readOnly = true} is not decoration. It lets Hibernate skip dirty-checking
     * (no snapshot of every loaded entity is kept for comparison at flush) and tells
     * PostgreSQL the transaction will not write. On a list endpoint loading hundreds of
     * rows, the skipped snapshotting is real allocation avoided.
     */
    @Transactional(readOnly = true)
    public OrderEntity findOrder(UUID orderId, String accountId) {
        return orderRepository.findByOrderIdAndAccountId(orderId, accountId)
                .orElseThrow(() -> NotFoundException.order(orderId));
    }

    @Transactional(readOnly = true)
    public Page<OrderEntity> search(String accountId, String symbol, OrderStatus status,
                                    Pageable pageable) {
        return orderRepository.search(accountId, symbol, status, pageable);
    }

    @Transactional(readOnly = true)
    public List<OrderAuditEntity> auditTrail(UUID orderId, String accountId) {
        // Scoped through the order so one account cannot read another account's history.
        findOrder(orderId, accountId);
        return auditRepository.findByOrderIdOrderBySeqAsc(orderId);
    }

    /**
     * Used by the Kafka listeners, which load an order by id alone: an event from the
     * engine is not scoped to a caller, and the account on the event is the authority.
     *
     * <p>{@code Propagation.MANDATORY} asserts the caller already opened a transaction.
     * If someone later calls this from outside one, it fails immediately and loudly rather
     * than quietly running each repository call in its own transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderEntity requireOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> NotFoundException.order(orderId));
    }

    private static String actor(String submittedBy) {
        return submittedBy == null || submittedBy.isBlank() ? ACTOR_API : submittedBy;
    }
}
