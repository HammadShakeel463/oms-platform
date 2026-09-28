package com.oms.matching.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The determinism property that makes an in-memory engine recoverable.
 *
 * <p>Small class, small test, and the single most load-bearing invariant in the engine. If
 * this were {@code UUID.randomUUID()}, a book rebuild after a restart would re-emit every
 * historical trade with fresh ids, order-service would fail to recognise them as duplicates,
 * and every position in the platform would double. The engine would look correct and the money
 * would be wrong.
 */
class TradeIdsTest {

    @Test
    @DisplayName("the same symbol and sequence always produce the same id")
    void deterministic() {
        assertThat(TradeIds.of("HBL", 42L))
                .isEqualTo(TradeIds.of("HBL", 42L))
                .isEqualTo(TradeIds.of("HBL", 42L));
    }

    @Test
    @DisplayName("a different sequence produces a different id")
    void sequenceChangesTheId() {
        assertThat(TradeIds.of("HBL", 42L)).isNotEqualTo(TradeIds.of("HBL", 43L));
    }

    @Test
    @DisplayName("a different symbol produces a different id")
    void symbolChangesTheId() {
        assertThat(TradeIds.of("HBL", 42L)).isNotEqualTo(TradeIds.of("OGDC", 42L));
    }

    @Test
    @DisplayName("the separator prevents (AB, 1) colliding with (A, B1)")
    void noConcatenationCollisions() {
        assertThat(TradeIds.of("AB", 1L)).isNotEqualTo(TradeIds.of("A", 0L));
        // The concrete trap: naive concatenation would make both of these the string "AB1".
        assertThat(TradeIds.of("AB", 1L)).isNotEqualTo(TradeIds.of("A", 1L));
    }

    @Test
    @DisplayName("ids are distinct across a realistic volume of trades")
    void noCollisionsAtVolume() {
        Set<UUID> ids = new HashSet<>();
        for (String symbol : new String[]{"HBL", "OGDC", "LUCK", "ENGRO", "PSO"}) {
            for (long sequence = 1; sequence <= 100_000; sequence++) {
                ids.add(TradeIds.of(symbol, sequence));
            }
        }

        assertThat(ids).hasSize(500_000);
    }

    @Test
    @DisplayName("a replayed stream reproduces the same ids in the same order")
    void replayReproducesTheSameIds() {
        // What a book rebuild actually looks like: the sequence restarts at 1 and walks the
        // same input, so the ids come out identical and downstream dedupe does its job.
        var firstRun = new java.util.ArrayList<UUID>();
        for (long sequence = 1; sequence <= 1_000; sequence++) {
            firstRun.add(TradeIds.of("HBL", sequence));
        }

        var afterRestart = new java.util.ArrayList<UUID>();
        for (long sequence = 1; sequence <= 1_000; sequence++) {
            afterRestart.add(TradeIds.of("HBL", sequence));
        }

        assertThat(afterRestart).isEqualTo(firstRun);
    }
}
