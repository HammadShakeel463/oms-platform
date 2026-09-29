package com.oms.marketdata.config;

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
 * Redis caching for reference data.
 *
 * <p>JSON serialisation rather than Spring's default JDK serialisation, for the same reasons as in
 * order-service: JDK-serialised bytes are opaque to {@code redis-cli}, break when a class changes
 * shape, and deserialising untrusted ones is a remote-code-execution primitive.
 *
 * <p>This configuration is deliberately <em>not</em> shared with order-service. The two services
 * cache different things with different staleness tolerances - five minutes for an instrument that
 * order-service validates against, an hour for the instrument list served to a UI - and a shared
 * module would be twenty lines of indirection wrapped around two numbers that need to differ. The
 * serialiser choice is repeated; the policy is local, which is where it belongs.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          ObjectMapper objectMapper) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .disableCachingNullValues()
                .prefixCacheNameWith("oms:marketdata:")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(objectMapper)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withCacheConfiguration("instruments", defaults.entryTtl(Duration.ofMinutes(5)))
                // The full list changes only when an instrument is added or halted, and it is a
                // UI-facing query, so a longer TTL is free.
                .withCacheConfiguration("instrument-list", defaults.entryTtl(Duration.ofHours(1)))
                .build();
    }
}
