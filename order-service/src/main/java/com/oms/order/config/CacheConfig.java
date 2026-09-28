package com.oms.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis-backed caching for reference data.
 *
 * <p>Two choices here are worth defending.
 *
 * <p><b>JSON serialisation, not Java serialisation.</b> Spring's default is
 * {@code JdkSerializationRedisSerializer}. It requires every cached type to implement
 * {@code Serializable}, produces opaque bytes nobody can inspect with {@code redis-cli}, and
 * breaks whenever a class changes shape. Worse, deserialising untrusted Java-serialised
 * bytes is a remote-code-execution primitive - if anything can write to the cache, it can
 * run code in this process. JSON has none of those properties.
 *
 * <p><b>A per-cache TTL, and it is short.</b> Reference data changes rarely, so a long TTL
 * looks free - but a corporate action, a halt or a band change would then take hours to
 * reach the order path. Five minutes bounds the staleness of a value that pre-trade risk
 * depends on. The cache exists to survive a market-data-service restart, not to avoid
 * every network call.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String INSTRUMENTS_CACHE = "instruments";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          ObjectMapper objectMapper) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .disableCachingNullValues()
                .prefixCacheNameWith("oms:order:")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(objectMapper)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withCacheConfiguration(INSTRUMENTS_CACHE, defaults.entryTtl(Duration.ofMinutes(5)))
                .build();
    }
}
