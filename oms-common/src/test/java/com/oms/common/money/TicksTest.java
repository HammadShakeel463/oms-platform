package com.oms.common.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicksTest {

    @ParameterizedTest
    @CsvSource({
            "172.45,   1724500",
            "0.0001,   1",
            "1,        10000",
            "9999.9999, 99999999",
            "-5.25,    -52500"
    })
    @DisplayName("decimal prices round-trip through ticks exactly")
    void roundTrip(String decimal, long expectedTicks) {
        BigDecimal price = new BigDecimal(decimal);
        long ticks = Ticks.fromDecimal(price);

        assertThat(ticks).isEqualTo(expectedTicks);
        assertThat(Ticks.toDecimal(ticks)).isEqualByComparingTo(price);
    }

    @Test
    @DisplayName("a price finer than the tick scale is rejected, never silently rounded")
    void rejectsUnrepresentablePrecision() {
        assertThatThrownBy(() -> Ticks.fromDecimal(new BigDecimal("172.456789")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not representable");
    }

    @Test
    @DisplayName("null decimal maps to the NO_PRICE sentinel and back")
    void noPriceSentinel() {
        assertThat(Ticks.fromDecimal(null)).isEqualTo(Ticks.NO_PRICE);
        assertThat(Ticks.toDecimal(Ticks.NO_PRICE)).isNull();
    }

    @Test
    @DisplayName("notional is exact for realistic sizes")
    void notional() {
        long price = Ticks.fromDecimal(new BigDecimal("172.4500"));
        assertThat(Ticks.notional(1_000, price)).isEqualByComparingTo(new BigDecimal("172450.0000"));
    }

    @Test
    @DisplayName("notional overflow throws instead of wrapping to a negative value")
    void notionalOverflowIsLoud() {
        assertThatThrownBy(() -> Ticks.notionalTicks(Long.MAX_VALUE / 2, 1_000_000L))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("format renders ticks without allocating a BigDecimal")
    void formatting() {
        assertThat(Ticks.format(1_724_500L)).isEqualTo("172.4500");
        assertThat(Ticks.format(1L)).isEqualTo("0.0001");
        assertThat(Ticks.format(Ticks.NO_PRICE)).isEqualTo("-");
    }
}
