package com.oms.position.service;

import com.oms.common.domain.Side;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.money.Ticks;
import com.oms.position.domain.PositionEntity;
import com.oms.position.domain.TradeLedgerEntry;
import com.oms.position.repository.PositionRepository;
import com.oms.position.repository.TradeLedgerRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PositionServiceTest {

    private static final String BUYER = "ACC-BUYER";
    private static final String SELLER = "ACC-SELLER";
    private static final String SYMBOL = "HBL";

    @Mock
    private PositionRepository positionRepository;
    @Mock
    private TradeLedgerRepository ledgerRepository;

    private MarkPriceCache markPriceCache;
    private PositionService service;

    @BeforeEach
    void setUp() {
        markPriceCache = new MarkPriceCache(new SimpleMeterRegistry());
        service = new PositionService(positionRepository, ledgerRepository,
                markPriceCache, new SimpleMeterRegistry());

        when(ledgerRepository.existsById(any())).thenReturn(false);
        when(positionRepository.findById(any())).thenReturn(Optional.empty());
        when(positionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ledgerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private TradeExecutedEvent trade(String price, long quantity, String buyer, String seller) {
        return new TradeExecutedEvent(
                UUID.randomUUID().toString(), Instant.parse("2026-09-29T09:15:00Z"), 1,
                UUID.randomUUID(), SYMBOL,
                Ticks.fromDecimal(new BigDecimal(price)), quantity, Side.BUY,
                UUID.randomUUID(), buyer,
                UUID.randomUUID(), seller,
                7L);
    }

    @Test
    @DisplayName("one trade creates a long for the buyer and a short for the seller")
    void bothSidesAreApplied() {
        service.applyTrade(trade("172.0000", 500, BUYER, SELLER));

        ArgumentCaptor<PositionEntity> captor = ArgumentCaptor.forClass(PositionEntity.class);
        verify(positionRepository, times(2)).save(captor.capture());

        List<PositionEntity> saved = captor.getAllValues();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getAccountId()).isEqualTo(BUYER);
        assertThat(saved.get(0).getNetQuantity()).isEqualTo(500);
        assertThat(saved.get(1).getAccountId()).isEqualTo(SELLER);
        assertThat(saved.get(1).getNetQuantity())
                .as("the seller is short the same quantity - a trade must balance")
                .isEqualTo(-500);
    }

    @Test
    @DisplayName("a ledger row is written per account, with the position state after the fill")
    void ledgerRecordsBothSides() {
        service.applyTrade(trade("172.0000", 500, BUYER, SELLER));

        ArgumentCaptor<TradeLedgerEntry> captor = ArgumentCaptor.forClass(TradeLedgerEntry.class);
        verify(ledgerRepository, times(2)).save(captor.capture());

        assertThat(captor.getAllValues())
                .extracting(TradeLedgerEntry::getAccountId, TradeLedgerEntry::getSide,
                        TradeLedgerEntry::getNetAfter)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BUYER, Side.BUY, 500L),
                        org.assertj.core.groups.Tuple.tuple(SELLER, Side.SELL, -500L));
        assertThat(captor.getAllValues().get(0).getAvgCostAfter()).isEqualByComparingTo("172.0000");
    }

    @Test
    @DisplayName("a replayed trade is ignored - the ledger key is the idempotency guard")
    void replayedTradeIsIgnored() {
        when(ledgerRepository.existsById(any())).thenReturn(true);

        service.applyTrade(trade("172.0000", 500, BUYER, SELLER));

        verify(positionRepository, never()).save(any());
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("a replay of one side only applies the other")
    void partialReplayAppliesOnlyTheMissingSide() {
        // The state after a crash between the two side writes. Both sides are in one
        // transaction, so this cannot happen in practice - but the handler must be robust to it
        // rather than assume its own transactionality.
        when(ledgerRepository.existsById(any()))
                .thenReturn(true)      // buyer already recorded
                .thenReturn(false);    // seller not

        service.applyTrade(trade("172.0000", 500, BUYER, SELLER));

        ArgumentCaptor<PositionEntity> captor = ArgumentCaptor.forClass(PositionEntity.class);
        verify(positionRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(SELLER);
    }

    @Test
    @DisplayName("an account trading with itself nets to flat, not to two separate positions")
    void selfTradeNetsToFlat() {
        // Unusual and legal: the same account on both sides of a trade through the book. The two
        // sides must be applied to the SAME row sequentially, or one update is lost.
        PositionEntity shared = new PositionEntity(BUYER, SYMBOL);
        when(positionRepository.findById(new PositionEntity.Key(BUYER, SYMBOL)))
                .thenReturn(Optional.of(shared));

        service.applyTrade(trade("172.0000", 500, BUYER, BUYER));

        assertThat(shared.getNetQuantity())
                .as("+500 then -500 on one position")
                .isZero();
        assertThat(shared.getFillCount()).isEqualTo(2);
        assertThat(shared.getOpenCost()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an existing position is loaded and extended, not replaced")
    void existingPositionIsExtended() {
        PositionEntity existing = new PositionEntity(BUYER, SYMBOL);
        existing.applyFill(Side.BUY, new BigDecimal("170.0000"), 100);
        when(positionRepository.findById(new PositionEntity.Key(BUYER, SYMBOL)))
                .thenReturn(Optional.of(existing));

        service.applyTrade(trade("180.0000", 100, BUYER, SELLER));

        assertThat(existing.getNetQuantity()).isEqualTo(200);
        assertThat(existing.averageCost()).isEqualByComparingTo("175.0000");
    }

    @Test
    @DisplayName("a never-traded symbol reads as a flat position, not a 404")
    void unknownSymbolIsFlatNotMissing() {
        PositionEntity position = service.positionFor(BUYER, "NOSUCH");

        assertThat(position.isFlat()).isTrue();
        assertThat(position.getSymbol()).isEqualTo("NOSUCH");
        assertThat(position.getRealisedPnl()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the mark comes from the tick cache, and is absent until a tick arrives")
    void markIsAbsentUntilTicked() {
        assertThat(service.markFor(SYMBOL)).isEmpty();

        markPriceCache.update(new com.oms.common.event.MarketTickEvent(
                "e1", Instant.now(), 1, SYMBOL,
                Ticks.fromDecimal(new BigDecimal("171.9000")), 100,
                Ticks.fromDecimal(new BigDecimal("172.1000")), 100,
                Ticks.fromDecimal(new BigDecimal("172.0000")), 50, 1L));

        assertThat(service.markFor(SYMBOL))
                .as("the mark is the mid of the two-sided quote, not the last trade")
                .contains(new BigDecimal("172.0000"));
    }
}
