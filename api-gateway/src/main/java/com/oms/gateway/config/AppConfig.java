package com.oms.gateway.config;

import com.oms.gateway.auth.AuthProperties;
import com.oms.gateway.auth.SigningKeys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The signing key pair.
     *
     * <p>A singleton bean, so the whole application signs and verifies with one key. Generated at
     * startup: no private key is committed to this repository, and the cost - tokens do not survive
     * a restart - is documented on SigningKeys and in ADR 0007.
     */
    @Bean
    public SigningKeys signingKeys() {
        return SigningKeys.generate();
    }
}
