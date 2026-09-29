package com.oms.position.service;

import com.oms.common.event.MarketTickEvent;
import com.oms.common.money.Ticks;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MarkPriceCacheTest {

    private MarkPriceCache cache;

    @BeforeEach
    void setUp() {
        cache = new MarkPriceCache(new SimpleMeterRegistry());
    }

    private static MarketTickEvent tick(String symbol, long sequence,
                                        String bid, String ask, String last) {
        return new MarketTickEvent("e-" + sequence, Instant.now(), 1, symbol,
                bid == null ? Ticks.NO_PRICE : Ticks.fromDecimal(new BigDecimal(bid)), 100,
                ask == null ? Ticks.NO_PRICE : Ticks.fromDecimal(new BigDecimal(ask)), 100,
                Ticks.fromDecimal(new BigDecimal(last)), 50, sequence);
    }

    @Test
    @DisplayName("the mark is the mid of a two-sided quote, not the last trade")
    void marksTheMid() {
        cache.update(tick("HBL", 1, "171.0000", "173.0000", "180.0000"));

        assertThat(cache.priceOf("HBL"))
                .as("a mid from a live quote is the price a position could be closed at; the last "
                        + "trade may be stale or a single print at an outlier")
                .contains(new BigDecimal("172.0000"));
    }

    @Test
    @DisplayName("with no two-sided market it falls back to the last trade")
    void fallsBackToLastTrade() {
        cache.update(tick("HBL", 1, null, "173.0000", "170.5000"));

        assertThat(cache.priceOf("HBL")).contains(new BigDecimal("170.5000"));
    }

    @Test
    @DisplayName("an unticked symbol is absent, not zero")
    void untickedSymbolIsAbsent() {
        assertThat(cache.priceOf("NOSUCH")).isEmpty();
        assertThat(cache.markOf("NOSUCH")).isEmpty();
    }

    @Test
    @DisplayName("a newer tick replaces an older one")
    void newerSequenceWins() {
        cache.update(tick("HBL", 1, "171.0000", "173.0000", "172.0000"));
        cache.update(tick("HBL", 2, "175.0000", "177.0000", "176.0000"));

        assertThat(cache.priceOf("HBL")).contains(new BigDecimal("176.0000"));
    }

    @Test
    @DisplayName("an out-of-order tick is ignored rather than rewinding the mark")
    void olderSequenceIsIgnored() {
        cache.update(tick("HBL", 10, "175.0000", "177.0000", "176.0000"));
        cache.update(tick("HBL", 4, "160.0000", "162.0000", "161.0000"));

        assertThat(cache.priceOf("HBL"))
                .as("a multi-threaded batch consumer can deliver across partitions out of order; "
                        + "marking a position at a rewound price is worse than a 100ms-old one")
                .contains(new BigDecimal("176.0000"));
        assertThat(cache.markOf("HBL")).get()
                .extracting(MarkPriceCache.Mark::sequence).isEqualTo(10L);
    }

    @Test
    @DisplayName("symbols are independent")
    void symbolsAreIndependent() {
        cache.update(tick("HBL", 1, "171.0000", "173.0000", "172.0000"));
        cache.update(tick("OGDC", 1, "205.0000", "207.0000", "206.0000"));

        assertThat(cache.priceOf("HBL")).contains(new BigDecimal("172.0000"));
        assertThat(cache.priceOf("OGDC")).contains(new BigDecimal("206.0000"));
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a tick with no usable price at all is discarded")
    void unusableTickIsDiscarded() {
        MarketTickEvent empty = new MarketTickEvent("e", Instant.now(), 1, "HBL",
                Ticks.NO_PRICE, 0, Ticks.NO_PRICE, 0, Ticks.NO_PRICE, 0, 1L);

        cache.update(empty);

        assertThat(cache.priceOf("HBL")).isEmpty();
    }
}
