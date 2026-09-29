package com.oms.marketdata.messaging;

import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.event.Topics;
import com.oms.marketdata.simulator.TickGenerator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Folds executed trades into the quote as the last traded price.
 *
 * <p>Idempotency needs no bookkeeping here, and that is worth stating rather than assuming: the
 * effect of this handler is "set the last traded price", which is last-value-wins by construction.
 * Applying the same trade twice produces the same state. This is the one consumer in the platform
 * that is <em>naturally</em> idempotent, which is why it has no processed-event table and no
 * natural-key constraint - both would be ceremony around an assignment.
 *
 * <p>The handover into the simulator is through a single-slot atomic reference rather than a lock
 * (see {@code SymbolState}), so this Kafka thread can never block tick generation.
 */
@Component
public class TradePrintListener {

    private static final Logger log = LoggerFactory.getLogger(TradePrintListener.class);

    private final TickGenerator tickGenerator;
    private final Counter prints;

    public TradePrintListener(TickGenerator tickGenerator, MeterRegistry meterRegistry) {
        this.tickGenerator = tickGenerator;
        this.prints = meterRegistry.counter("oms.marketdata.trade.prints");
    }

    @KafkaListener(
            topics = Topics.TRADES_EXECUTED,
            groupId = "${oms.marketdata.trade-consumer-group:marketdata-prints}",
            containerFactory = "tradePrintListenerFactory")
    public void onTrade(TradeExecutedEvent event) {
        if (event == null) {
            return;
        }
        tickGenerator.recordTradePrint(event.symbol(), event.priceTicks(), event.quantity());
        prints.increment();
        log.debug("Printed {} x {} @ {} into the {} quote",
                event.symbol(), event.quantity(), event.priceTicks(), event.symbol());
    }
}
