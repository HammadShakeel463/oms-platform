package com.oms.marketdata.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(MarketDataProperties.class)
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
