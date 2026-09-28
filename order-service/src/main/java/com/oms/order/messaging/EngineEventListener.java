package com.oms.order.messaging;

import com.oms.common.event.OrderCancelConfirmedEvent;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.event.Topics;
import com.oms.order.service.TradeApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes what the matching engine reports.
 *
 * <p>The listener methods are deliberately thin: pull the payload out of Kafka, set up
 * logging context, delegate. All the business logic and the transaction live in
 * {@link TradeApplicationService}, which can therefore be unit-tested with no broker and no
 * Spring context at all. A listener with logic inside it can only be tested by starting a
 * consumer.
 *
 * <p>Note the transaction boundary is <em>inside</em> the delegate, not on the listener.
 * The container commits the Kafka offset after this method returns; the database
 * transaction commits when the delegate returns. Between those two moments a crash replays
 * the record - which is precisely why the delegate is idempotent.
 */
@Component
public class EngineEventListener {

    private static final Logger log = LoggerFactory.getLogger(EngineEventListener.class);

    private final TradeApplicationService tradeApplicationService;

    public EngineEventListener(TradeApplicationService tradeApplicationService) {
        this.tradeApplicationService = tradeApplicationService;
    }

    @KafkaListener(
            topics = Topics.TRADES_EXECUTED,
            groupId = "${oms.order.trade-consumer-group:order-service-fills}",
            containerFactory = "tradeListenerFactory")
    public void onTrade(TradeExecutedEvent event,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        try (var ignored = MDC.putCloseable("tradeId", String.valueOf(event.tradeId()))) {
            log.debug("Trade {} {}x{} @ {} (partition {}, offset {})",
                    event.tradeId(), event.symbol(), event.quantity(), event.priceTicks(),
                    partition, offset);
            tradeApplicationService.applyTrade(event);
        }
    }

    @KafkaListener(
            topics = Topics.EXECUTION_REPORTS,
            groupId = "${oms.order.trade-consumer-group:order-service-fills}",
            containerFactory = "executionReportListenerFactory")
    public void onCancelConfirmed(OrderCancelConfirmedEvent event) {
        try (var ignored = MDC.putCloseable("orderId", String.valueOf(event.orderId()))) {
            tradeApplicationService.applyCancelConfirmation(event);
        }
    }
}
