package com.oms.marketdata.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.marketdata.QuoteSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Redis quote cache.
 *
 * <p>Every test here is really about one property: <b>the cache is never allowed to fail the
 * caller</b>. A quote cache exists to make a read cheap, and a cold or unreachable cache degrades
 * latency without affecting correctness - so Redis being down has to look like a miss, not like a
 * 500. Removing a service from rotation because a cache is unavailable would be an outage
 * <em>caused by</em> the cache, which is also why readiness deliberately excludes Redis.
 */
@ExtendWith(MockitoExtension.class)
class QuoteCacheTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper objectMapper;
    private MeterRegistry meterRegistry;
    private QuoteCache cache;

    @BeforeEach
    void setUp() {
        objectMapper = Jackson2ObjectMapperBuilder.json().build();
        meterRegistry = new SimpleMeterRegistry();
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        cache = new QuoteCache(redis, objectMapper, meterRegistry, Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("a snapshot is written under a namespaced key with the configured TTL")
    void writesWithTtl() {
        cache.put(snapshot("HBL", 7L));

        verify(valueOps).set(eq("oms:quote:HBL"), anyString(), eq(Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("the TTL is a safety net: a crashed simulator must not serve a stale price for ever")
    void ttlIsAlwaysApplied() {
        QuoteCache shortLived = new QuoteCache(redis, objectMapper, meterRegistry,
                Duration.ofSeconds(5));

        shortLived.put(snapshot("HBL", 1L));

        verify(valueOps).set(anyString(), anyString(), eq(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("a cached quote round-trips with its prices and sequence intact")
    void readRoundTrips() throws Exception {
        QuoteSnapshot original = snapshot("HBL", 42L);
        when(valueOps.get("oms:quote:HBL")).thenReturn(objectMapper.writeValueAsString(original));

        assertThat(cache.get("HBL")).contains(original);
        assertThat(count("hit")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an absent key is a miss, counted as a miss")
    void absentKeyIsAMiss() {
        when(valueOps.get("oms:quote:NOPE")).thenReturn(null);

        assertThat(cache.get("NOPE")).isEmpty();
        assertThat(count("miss")).isEqualTo(1.0);
        assertThat(count("hit")).isZero();
    }

    @Test
    @DisplayName("Redis being unreachable on read looks like a miss, never like a failure")
    void unreachableRedisOnReadIsAMiss() {
        when(valueOps.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(cache.get("HBL"))
                .as("a cache outage must degrade latency, not correctness")
                .isEmpty();
        assertThat(count("error")).isEqualTo(1.0);
        assertThat(count("miss"))
                .as("an error is not a miss: conflating them hides a cache outage behind a "
                        + "plausible-looking hit ratio")
                .isZero();
    }

    @Test
    @DisplayName("Redis being unreachable on write is swallowed - the tick still goes to Kafka")
    void unreachableRedisOnWriteIsSwallowed() {
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        cache.put(snapshot("HBL", 1L));

        assertThat(count("error")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("corrupt cached JSON is a miss, not an exception thrown at the caller")
    void corruptEntryIsAMiss() {
        when(valueOps.get("oms:quote:HBL")).thenReturn("{not json");

        assertThat(cache.get("HBL")).isEmpty();
        assertThat(count("error")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("hits and misses are one metric tagged by result, so a hit ratio is a single query")
    void hitRatioIsDerivable() throws Exception {
        when(valueOps.get("oms:quote:HBL"))
                .thenReturn(objectMapper.writeValueAsString(snapshot("HBL", 1L)));
        when(valueOps.get("oms:quote:ENGRO")).thenReturn(null);

        cache.get("HBL");
        cache.get("HBL");
        cache.get("ENGRO");

        assertThat(count("hit")).isEqualTo(2.0);
        assertThat(count("miss")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("keys are namespaced, so the quote cache cannot collide with anything else in Redis")
    void keysAreNamespaced() {
        cache.get("HBL");

        verify(valueOps).get("oms:quote:HBL");
    }

    private double count(String result) {
        return meterRegistry.counter("oms.marketdata.quote.cache", "result", result).count();
    }

    private static QuoteSnapshot snapshot(String symbol, long sequence) {
        return new QuoteSnapshot(symbol,
                new BigDecimal("172.4000"), 1_000,
                new BigDecimal("172.5000"), 800,
                new BigDecimal("172.4500"), 200,
                sequence, Instant.parse("2026-09-28T09:15:30Z"));
    }
}
