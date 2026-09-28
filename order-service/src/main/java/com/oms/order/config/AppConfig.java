package com.oms.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(OrderProperties.class)
public class AppConfig {

    /**
     * A {@code Clock} bean rather than calls to {@code Instant.now()}.
     *
     * <p>Time is a dependency. Injecting it means a test can freeze it and assert exact
     * timestamps and ordering without sleeping, which is the difference between a
     * deterministic test suite and a flaky one. The cost is one bean; the C++ habit of
     * templating on a clock type gets the same property at compile time.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The HTTP client used for reference-data lookups.
     *
     * <p>Timeouts are set explicitly. The default on most Java HTTP clients is "wait
     * forever", and a synchronous call with no read timeout on the order path means one
     * unresponsive dependency can occupy every request thread until the pool is gone -
     * the classic cascading failure. Two seconds is generous for a cached lookup on the
     * same network and short enough that exhausting the pool takes deliberate effort.
     */
    @Bean
    public RestClient marketDataRestClient(
            @org.springframework.beans.factory.annotation.Value(
                    "${oms.reference.market-data-url:http://localhost:8083}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory((ClientHttpRequestFactory) factory)
                .build();
    }
}
