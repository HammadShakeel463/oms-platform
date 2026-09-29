package com.oms.marketdata.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.marketdata.QuoteSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Last known quote per symbol, in Redis.
 *
 * <h2>Why Redis when the state is already in memory</h2>
 *
 * <p>The tick generator holds live state in {@code SymbolState}, so the obvious question is what
 * Redis adds. Three things:
 *
 * <ul>
 *   <li><b>Any instance can answer.</b> The snapshot endpoint is behind the gateway, which does not
 *       know which instance is generating which symbol. Redis makes every instance able to serve
 *       every symbol.</li>
 *   <li><b>A restart is not a blackout.</b> A fresh instance has no walk state, and order-service
 *       prices market orders against the last quote. A cached value that is two seconds old is far
 *       better than none.</li>
 *   <li><b>It is the shared read model</b> for anything that wants a quote without subscribing to
 *       the stream.</li>
 * </ul>
 *
 * <h2>Two deliberate choices</h2>
 *
 * <p><b>Explicit JSON, not {@code @Cacheable}.</b> Quotes are <em>written through</em> by the
 * generator on every tick, not populated lazily on a cache miss. {@code @Cacheable} models
 * read-through caching; this is a publication. Using the annotation here would mean the cache only
 * ever held symbols somebody had already asked for.
 *
 * <p><b>A short TTL, and it is a safety mechanism rather than a memory one.</b> If the generator
 * stops - crash, halt, deploy - an entry with no TTL would sit in Redis for ever and be served as
 * though it were current. The TTL converts "the feed died" into "no quote available", which is an
 * answer a caller can act on. A stale price served confidently is the failure mode that costs
 * money.
 *
 * <p>Every Redis call is wrapped: a cache must never be able to fail the request that uses it.
 */
@Component
public class QuoteCache {

    private static final Logger log = LoggerFactory.getLogger(QuoteCache.class);
    private static final String KEY_PREFIX = "oms:quote:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public QuoteCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                      MeterRegistry meterRegistry,
                      @org.springframework.beans.factory.annotation.Value(
                              "${oms.marketdata.quote-cache-ttl:30s}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.hits = meterRegistry.counter("oms.marketdata.quote.cache", "result", "hit");
        this.misses = meterRegistry.counter("oms.marketdata.quote.cache", "result", "miss");
        this.errors = meterRegistry.counter("oms.marketdata.quote.cache", "result", "error");
    }

    public void put(QuoteSnapshot snapshot) {
        try {
            redis.opsForValue().set(key(snapshot.symbol()),
                    objectMapper.writeValueAsString(snapshot), ttl);
        } catch (Exception e) {
            // Logged at DEBUG on purpose. If Redis is down this fires on every tick for every
            // symbol; at WARN it would bury everything else in the log and tell the operator
            // nothing the connection-failure metric does not already say.
            errors.increment();
            log.debug("Could not cache quote for {}: {}", snapshot.symbol(), e.toString());
        }
    }

    public Optional<QuoteSnapshot> get(String symbol) {
        try {
            String json = redis.opsForValue().get(key(symbol));
            if (json == null) {
                misses.increment();
                return Optional.empty();
            }
            hits.increment();
            return Optional.of(objectMapper.readValue(json, QuoteSnapshot.class));
        } catch (Exception e) {
            errors.increment();
            log.debug("Could not read cached quote for {}: {}", symbol, e.toString());
            return Optional.empty();
        }
    }

    private static String key(String symbol) {
        return KEY_PREFIX + symbol;
    }
}
