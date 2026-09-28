package com.oms.matching.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(EngineProperties.class)
public class AppConfig {

    /**
     * Time as an injected dependency, so a test can freeze it. Same reasoning as in
     * order-service: a deterministic clock is the difference between a test suite that
     * asserts timestamps and one that sleeps and hopes.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
