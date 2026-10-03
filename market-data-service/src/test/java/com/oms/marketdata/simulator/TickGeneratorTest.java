package com.oms.marketdata.simulator;

import com.oms.common.event.MarketTickEvent;
import com.oms.common.marketdata.QuoteSnapshot;
import com.oms.common.money.Ticks;
import com.oms.common.reference.InstrumentStatus;
import com.oms.marketdata.config.MarketDataProperties;
import com.oms.marketdata.domain.InstrumentEntity;
import com.oms.marketdata.messaging.TickPublisher;
import com.oms.marketdata.quote.QuoteCache;
import com.oms.marketdata.reference.InstrumentService;
import com.oms.marketdata.stream.TickBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The simulated feed's pass over every active instrument.
 *
 * <p>The behaviour worth asserting is the <b>fan-out contract</b>: one generated tick goes to
 * Kafka, to the Redis snapshot cache and to the SSE broadcaster, and all three get the same
 * sequence. A tick that reached two of the three would mean the REST snapshot and the stream
 * disagree about the current price, which is the kind of inconsistency that gets noticed as "the
 * UI is wrong" long after the cause is forgettable.
 *
 * <p>The error-handling test matters for a structural reason: a {@code @Scheduled} method that
 * throws can stop being rescheduled, so a single failed database call would silently end the feed
 * for the life of the process.
 */
@ExtendWith(MockitoExtension.class)
class TickGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-09-28T09:15:00Z");

    @Mock
    private InstrumentService instrumentService;
    @Mock
    private TickPublisher publisher;
    @Mock
    private QuoteCache quoteCache;
    @Mock
    private TickBroadcaster broadcaster;

    @Captor
    private ArgumentCaptor<MarketTickEvent> publishedTick;
    @Captor
    private ArgumentCaptor<QuoteSnapshot> cachedSnapshot;

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("one pass publishes one tick per active instrument, to all three destinations")
    void onePassFansOutToEveryDestination() {
        TickGenerator generator = generator(true);
        when(instrumentService.activeInstruments())
                .thenReturn(List.of(instrument("HBL", "172.4500"), instrument("ENGRO", "280.0000")));

        generator.generateTicks();

        verify(publisher, atLeastOnce()).publish(publishedTick.capture());
        assertThat(publishedTick.getAllValues()).hasSize(2)
                .extracting(MarketTickEvent::symbol).containsExactly("HBL", "ENGRO");
        verify(quoteCache, atLeastOnce()).put(any(QuoteSnapshot.class));
        verify(broadcaster, atLeastOnce()).publish(any(MarketTickEvent.class));
        assertThat(generator.symbolCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the Kafka tick and the cached snapshot carry the same sequence")
    void kafkaAndCacheAgree() {
        TickGenerator generator = generator(true);
        when(instrumentService.activeInstruments()).thenReturn(List.of(instrument("HBL", "172.4500")));

        generator.generateTicks();

        verify(publisher).publish(publishedTick.capture());
        verify(quoteCache).put(cachedSnapshot.capture());
        assertThat(cachedSnapshot.getValue().sequence())
                .as("a stream and a snapshot that disagree present as 'the UI is wrong'")
                .isEqualTo(publishedTick.getValue().sequence());
        assertThat(cachedSnapshot.getValue().symbol()).isEqualTo("HBL");
    }

    @Test
    @DisplayName("a generated quote is never crossed, and its sizes are positive")
    void generatedQuotesAreWellFormed() {
        TickGenerator generator = generator(true);
        when(instrumentService.activeInstruments()).thenReturn(List.of(instrument("HBL", "172.4500")));

        for (int pass = 0; pass < 50; pass++) {
            generator.generateTicks();
        }

        verify(publisher, atLeastOnce()).publish(publishedTick.capture());
        assertThat(publishedTick.getAllValues()).allSatisfy(tick -> {
            assertThat(tick.bidPriceTicks())
                    .as("a crossed simulated book would make every downstream risk check nonsense")
                    .isLessThan(tick.askPriceTicks());
            assertThat(tick.bidPriceTicks()).isPositive();
            assertThat(tick.bidSize()).isPositive();
            assertThat(tick.askSize()).isPositive();
        });
    }

    @Test
    @DisplayName("the sequence increases monotonically per symbol")
    void sequenceIsMonotonic() {
        TickGenerator generator = generator(true);
        when(instrumentService.activeInstruments()).thenReturn(List.of(instrument("HBL", "172.4500")));

        for (int pass = 0; pass < 10; pass++) {
            generator.generateTicks();
        }

        verify(publisher, atLeastOnce()).publish(publishedTick.capture());
        assertThat(publishedTick.getAllValues()).extracting(MarketTickEvent::sequence)
                .isSorted()
                .doesNotHaveDuplicates()
                .endsWith(10L);
    }

    @Test
    @DisplayName("the simulator does no work at all when disabled - this is the switch for production")
    void disabledSimulatorIsInert() {
        TickGenerator generator = generator(false);

        generator.generateTicks();

        verify(instrumentService, never()).activeInstruments();
        verify(publisher, never()).publish(any());
        assertThat(generator.symbolCount()).isZero();
    }

    @Test
    @DisplayName("a failed pass is counted and swallowed, because a scheduled throw can end the feed")
    void failedPassDoesNotKillTheFeed() {
        TickGenerator generator = generator(true);
        when(instrumentService.activeInstruments())
                .thenThrow(new IllegalStateException("database unreachable"))
                .thenReturn(List.of(instrument("HBL", "172.4500")));

        generator.generateTicks();
        assertThat(meterRegistry.counter("oms.marketdata.generation.errors").count()).isEqualTo(1.0);

        generator.generateTicks();
        verify(publisher).publish(any(MarketTickEvent.class));
    }

    @Test
    @DisplayName("a symbol that stops being active is dropped, so the gauge does not leak")
    void inactiveSymbolsAreForgotten() {
        TickGenerator generator = generator(true);
        when(instrumentService.activeInstruments())
                .thenReturn(List.of(instrument("HBL", "172.4500"), instrument("ENGRO", "280.0000")))
                .thenReturn(List.of(instrument("HBL", "172.4500")));

        generator.generateTicks();
        assertThat(generator.symbolCount()).isEqualTo(2);

        generator.generateTicks();

        assertThat(generator.symbolCount())
                .as("a halted symbol keeping its state would report simulated depth for ever")
                .isEqualTo(1);
        assertThat(generator.liveSnapshot("ENGRO")).isEmpty();
    }

    @Test
    @DisplayName("liveSnapshot is empty before the first pass and present afterwards")
    void liveSnapshotFollowsState() {
        TickGenerator generator = generator(true);
        when(instrumentService.activeInstruments()).thenReturn(List.of(instrument("HBL", "172.4500")));

        assertThat(generator.liveSnapshot("HBL")).isEmpty();

        generator.generateTicks();

        assertThat(generator.liveSnapshot("HBL"))
                .isPresent()
                .get()
                .extracting(QuoteSnapshot::symbol).isEqualTo("HBL");
    }

    @Test
    @DisplayName("a real trade print moves the simulated last price on the next tick")
    void tradePrintIsAbsorbed() {
        TickGenerator generator = generator(true);
        when(instrumentService.activeInstruments()).thenReturn(List.of(instrument("HBL", "172.4500")));
        generator.generateTicks();

        long printPrice = Ticks.fromDecimal(new BigDecimal("175.0000"));
        generator.recordTradePrint("HBL", printPrice, 1_000);
        generator.generateTicks();

        verify(publisher, atLeastOnce()).publish(publishedTick.capture());
        MarketTickEvent after = publishedTick.getAllValues().get(publishedTick.getAllValues().size() - 1);
        assertThat(after.lastPriceTicks())
                .as("the market has spoken; a simulated quote that ignores a real print is useless")
                .isEqualTo(printPrice);
        assertThat(after.lastSize()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("a print for an unknown symbol is ignored rather than creating state")
    void printForUnknownSymbolIsIgnored() {
        TickGenerator generator = generator(true);

        generator.recordTradePrint("NOPE", 1_000L, 10);

        assertThat(generator.symbolCount()).isZero();
        assertThat(generator.liveSnapshot("NOPE")).isEmpty();
    }

    @Test
    @DisplayName("the pass is timed with percentiles, so a slow feed is visible as a distribution")
    void passIsTimed() {
        TickGenerator generator = generator(true);
        when(instrumentService.activeInstruments()).thenReturn(List.of(instrument("HBL", "172.4500")));

        generator.generateTicks();

        assertThat(meterRegistry.find("oms.marketdata.generation.pass").timer())
                .isNotNull()
                .extracting(timer -> timer.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("an empty instrument list is a valid pass, not an error")
    void noInstrumentsIsNotAnError() {
        TickGenerator generator = generator(true);
        when(instrumentService.activeInstruments()).thenReturn(List.of());

        generator.generateTicks();

        verify(publisher, never()).publish(any());
        assertThat(meterRegistry.counter("oms.marketdata.generation.errors").count()).isZero();
    }

    // ---------------------------------------------------------------------------------

    private TickGenerator generator(boolean simulatorEnabled) {
        lenient().when(broadcaster.subscriberCount()).thenReturn(0);
        return new TickGenerator(instrumentService, publisher, quoteCache, broadcaster,
                new MarketDataProperties(simulatorEnabled, 2, 3, 100,
                        Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofSeconds(15)),
                meterRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static InstrumentEntity instrument(String symbol, String referencePrice) {
        return new InstrumentEntity(symbol, symbol + " Limited", "PK000" + symbol, "PKR",
                1L, new BigDecimal("0.0100"), new BigDecimal("10.00"),
                new BigDecimal(referencePrice), InstrumentStatus.ACTIVE);
    }
}
