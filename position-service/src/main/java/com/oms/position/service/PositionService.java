package com.oms.position.service;

import com.oms.common.domain.Side;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.money.Ticks;
import com.oms.position.domain.PositionEntity;
import com.oms.position.domain.TradeLedgerEntry;
import com.oms.position.repository.PositionRepository;
import com.oms.position.repository.TradeLedgerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Applies fills to positions, and answers questions about them.
 *
 * <h2>Both sides of a trade in one transaction</h2>
 *
 * <p>A {@code TradeExecutedEvent} carries the buyer and the seller, and both are applied in one
 * transaction. That is not tidiness: a trade that is half applied does not balance, and a risk
 * report taken at that instant would show quantity appearing out of nothing. If the sell side fails,
 * the buy side rolls back with it and Kafka redelivers the whole record.
 *
 * <p>The two accounts on a trade are usually different, but they can be the same - an account
 * trading with itself through the book is unusual and legal. Applying both sides sequentially to the
 * same row in one transaction handles it correctly, where a "fetch both, then update both" approach
 * would lose one of the two updates.
 *
 * <h2>Ordering, and why symbol keying is enough</h2>
 *
 * <p>Average-cost P&amp;L is <b>order dependent</b>: buy 100@10, buy 100@12, sell 100@11 realises a
 * different figure from the same three fills in another order. Position <em>quantity</em> is
 * commutative; realised P&amp;L is not. So what must be ordered is the stream per
 * {@code (account, symbol)} pair - and since every trade for such a pair necessarily carries that
 * symbol, keying the topic by symbol already guarantees it. No repartitioning, no second topic. The
 * full argument is in docs/kafka-event-design.md §2.
 */
@Service
public class PositionService {

    private static final Logger log = LoggerFactory.getLogger(PositionService.class);

    private final PositionRepository positionRepository;
    private final TradeLedgerRepository ledgerRepository;
    private final MarkPriceCache markPriceCache;
    private final MeterRegistry meterRegistry;

    public PositionService(PositionRepository positionRepository,
                           TradeLedgerRepository ledgerRepository,
                           MarkPriceCache markPriceCache,
                           MeterRegistry meterRegistry) {
        this.positionRepository = positionRepository;
        this.ledgerRepository = ledgerRepository;
        this.markPriceCache = markPriceCache;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void applyTrade(TradeExecutedEvent trade) {
        BigDecimal price = Ticks.toDecimal(trade.priceTicks());
        applySide(trade, Side.BUY, price);
        applySide(trade, Side.SELL, price);
        meterRegistry.counter("oms.position.trades.applied", "symbol", trade.symbol()).increment();
    }

    private void applySide(TradeExecutedEvent trade, Side side, BigDecimal price) {
        String accountId = trade.accountIdFor(side);
        UUID orderId = trade.orderIdFor(side);

        TradeLedgerEntry.Key ledgerKey = new TradeLedgerEntry.Key(trade.tradeId(), accountId);
        if (ledgerRepository.existsById(ledgerKey)) {
            // The idempotency guard. Kafka delivers at-least-once, so this is the expected path
            // after a rebalance or a retry - not an anomaly.
            log.debug("Trade {} already applied for account {}, skipping (replay)",
                    trade.tradeId(), accountId);
            meterRegistry.counter("oms.position.trades.duplicate").increment();
            return;
        }

        PositionEntity position = positionRepository
                .findById(new PositionEntity.Key(accountId, trade.symbol()))
                .orElseGet(() -> new PositionEntity(accountId, trade.symbol()));

        long netBefore = position.getNetQuantity();
        BigDecimal realised = position.applyFill(side, price, trade.quantity());

        positionRepository.save(position);
        ledgerRepository.save(new TradeLedgerEntry(
                trade.tradeId(), accountId, trade.symbol(), orderId, side,
                price, trade.quantity(), realised,
                position.getNetQuantity(), position.averageCost(),
                trade.sequence(), trade.occurredAt()));

        if (realised.signum() != 0) {
            meterRegistry.counter("oms.position.realised",
                    "symbol", trade.symbol(),
                    "outcome", realised.signum() > 0 ? "profit" : "loss").increment();
        }

        // Crossing through zero is the interesting case and worth a log line at INFO: it is the
        // one that reveals an accounting bug, and it is rare enough not to be noisy.
        if (netBefore != 0 && Math.signum((float) netBefore) != Math.signum((float) position.getNetQuantity())
                && position.getNetQuantity() != 0) {
            log.info("Account {} crossed through zero in {}: {} -> {}, realised {}",
                    accountId, trade.symbol(), netBefore, position.getNetQuantity(),
                    realised.toPlainString());
        }

        log.debug("Applied {} {} x {} @ {} to {}/{}: net {}, avg {}, realised {}",
                side, trade.quantity(), trade.symbol(), price.toPlainString(),
                accountId, trade.symbol(), position.getNetQuantity(),
                position.averageCost().toPlainString(), realised.toPlainString());
    }

    // =================================================================================
    //  Queries
    // =================================================================================

    @Transactional(readOnly = true)
    public List<PositionEntity> positionsFor(String accountId, boolean openOnly) {
        return openOnly
                ? positionRepository.findByIdAccountIdAndNetQuantityNotOrderByIdSymbolAsc(accountId, 0L)
                : positionRepository.findByIdAccountIdOrderByIdSymbolAsc(accountId);
    }

    @Transactional(readOnly = true)
    public PositionEntity positionFor(String accountId, String symbol) {
        return positionRepository.findById(new PositionEntity.Key(accountId, symbol))
                // A never-traded symbol is a flat position, not a 404. "You hold none of this" is
                // the correct answer to "how much of this do I hold", and returning 404 would make
                // every client special-case it.
                .orElseGet(() -> new PositionEntity(accountId, symbol));
    }

    /** The mark used for unrealised P&L, or empty when the symbol has not ticked. */
    public java.util.Optional<BigDecimal> markFor(String symbol) {
        return markPriceCache.priceOf(symbol);
    }
}
