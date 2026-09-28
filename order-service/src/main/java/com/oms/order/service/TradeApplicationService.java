package com.oms.order.service;

import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.Side;
import com.oms.common.event.CancelReason;
import com.oms.common.event.OrderCancelConfirmedEvent;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.money.Ticks;
import com.oms.order.domain.Liquidity;
import com.oms.order.domain.OrderEntity;
import com.oms.order.domain.OrderFillEntity;
import com.oms.order.domain.ProcessedEvent;
import com.oms.order.repository.OrderFillRepository;
import com.oms.order.repository.OrderRepository;
import com.oms.order.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies what the matching engine reports back: fills and cancel confirmations.
 *
 * <p>Everything here must be safe to run twice. Kafka delivers at-least-once, the container
 * commits the offset only after the handler returns, and a crash between "transaction
 * committed" and "offset committed" replays the record. Two different idempotency
 * mechanisms are used, chosen per event:
 *
 * <ul>
 *   <li><b>Fills</b> - the natural key {@code (tradeId, orderId)} is the primary key of
 *       {@code order_fill}. The row itself records that the event was applied, so no extra
 *       bookkeeping is needed.</li>
 *   <li><b>Cancel confirmations</b> - no natural key, so an explicit {@code processed_event}
 *       row is written in the same transaction as the effect.</li>
 * </ul>
 *
 * <p>Preferring a natural key is not a micro-optimisation: an idempotency table that can
 * drift out of step with the effect it guards is a second source of truth. When the effect
 * itself carries the key, the two cannot disagree.
 */
@Service
public class TradeApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradeApplicationService.class);
    private static final String CONSUMER = "order-service-fills";
    private static final String ACTOR = "matching-engine";

    private final OrderRepository orderRepository;
    private final OrderFillRepository fillRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OrderLifecycleService lifecycle;
    private final ExposureService exposureService;
    private final MeterRegistry meterRegistry;

    public TradeApplicationService(OrderRepository orderRepository,
                                   OrderFillRepository fillRepository,
                                   ProcessedEventRepository processedEventRepository,
                                   OrderLifecycleService lifecycle,
                                   ExposureService exposureService,
                                   MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.fillRepository = fillRepository;
        this.processedEventRepository = processedEventRepository;
        this.lifecycle = lifecycle;
        this.exposureService = exposureService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Applies both sides of a trade in one transaction.
     *
     * <p>Both sides together, because the event carries both and a trade that is half
     * applied is a trade that does not balance. If the sell side fails, the buy side rolls
     * back with it and the whole record is retried.
     */
    @Transactional
    public void applyTrade(TradeExecutedEvent event) {
        applySide(event, Side.BUY);
        applySide(event, Side.SELL);
        meterRegistry.counter("oms.fills.applied", "symbol", event.symbol()).increment();
    }

    private void applySide(TradeExecutedEvent event, Side side) {
        UUID orderId = event.orderIdFor(side);

        Optional<OrderEntity> maybeOrder = orderRepository.findById(orderId);
        if (maybeOrder.isEmpty()) {
            // Not a reason to dead-letter the whole trade: the other side may be a
            // perfectly good order of ours, and a trade for an order this service has
            // never seen is a platform-level inconsistency to alert on, not to retry.
            log.error("Trade {} references unknown order {} on the {} side",
                    event.tradeId(), orderId, side);
            meterRegistry.counter("oms.fills.orphaned", "side", side.name()).increment();
            return;
        }
        OrderEntity order = maybeOrder.get();

        OrderFillEntity.Key fillKey = new OrderFillEntity.Key(event.tradeId(), orderId);
        if (fillRepository.existsById(fillKey)) {
            log.debug("Trade {} already applied to order {}, skipping (replay)",
                    event.tradeId(), orderId);
            meterRegistry.counter("oms.fills.duplicate").increment();
            return;
        }

        if (!order.getStatus().isLive()) {
            log.error("Fill for order {} which is {} - engine and order-service disagree",
                    orderId, order.getStatus());
            meterRegistry.counter("oms.fills.on-dead-order").increment();
            return;
        }

        BigDecimal price = Ticks.toDecimal(event.priceTicks());
        Liquidity liquidity = event.aggressor() == side ? Liquidity.TAKER : Liquidity.MAKER;

        fillRepository.save(new OrderFillEntity(
                event.tradeId(), orderId, event.symbol(), price, event.quantity(),
                liquidity, event.sequence(), event.occurredAt()));

        order.applyFill(price, event.quantity());
        exposureService.fillApplied(order.getAccountId(), order.getSymbol(), side, event.quantity());

        OrderStatus target = order.isFullyFilled()
                ? OrderStatus.FILLED
                : OrderStatus.PARTIALLY_FILLED;
        lifecycle.transition(order, target,
                "filled " + event.quantity() + " @ " + price.toPlainString()
                        + " (" + liquidity + ", trade " + event.tradeId() + ")",
                ACTOR);
    }

    /**
     * Applies a cancel confirmation from the engine. Only now does an order become
     * CANCELLED - the client request alone never moves it.
     */
    @Transactional
    public void applyCancelConfirmation(OrderCancelConfirmedEvent event) {
        ProcessedEvent.Key key = new ProcessedEvent.Key(CONSUMER, event.eventId());
        if (processedEventRepository.existsById(key)) {
            log.debug("Cancel confirmation {} already applied, skipping (replay)", event.eventId());
            return;
        }

        Optional<OrderEntity> maybeOrder = orderRepository.findById(event.orderId());
        if (maybeOrder.isEmpty()) {
            log.error("Cancel confirmation for unknown order {}", event.orderId());
            processedEventRepository.save(new ProcessedEvent(CONSUMER, event.eventId()));
            return;
        }
        OrderEntity order = maybeOrder.get();

        if (event.reason() == CancelReason.UNKNOWN_ORDER) {
            // The engine had no such order: it was already fully filled, or the accepted
            // event never arrived. Either way the engine is authoritative about its own
            // book and there is nothing to apply here.
            log.warn("Engine reports UNKNOWN_ORDER for {} (locally {})",
                    order.getOrderId(), order.getStatus());
            processedEventRepository.save(new ProcessedEvent(CONSUMER, event.eventId()));
            return;
        }

        if (order.getStatus().isTerminal()) {
            log.debug("Order {} is already {}, ignoring cancel confirmation",
                    order.getOrderId(), order.getStatus());
            processedEventRepository.save(new ProcessedEvent(CONSUMER, event.eventId()));
            return;
        }

        long released = order.leavesQuantity();
        lifecycle.transition(order, OrderStatus.CANCELLED,
                "cancelled by engine: " + event.reason(), ACTOR);
        exposureService.workingReleased(order.getAccountId(), order.getSymbol(),
                order.getSide(), released);

        processedEventRepository.save(new ProcessedEvent(CONSUMER, event.eventId()));
        meterRegistry.counter("oms.order.cancelled", "reason", event.reason().name()).increment();
        log.info("Order {} cancelled ({}), {} released", order.getOrderId(), event.reason(), released);
    }
}
