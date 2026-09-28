package com.oms.marketdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * market-data-service: simulates an exchange feed, publishes ticks to Kafka, serves Redis-cached snapshots and a backpressure-aware streaming endpoint.
 *
 * <p>@SpringBootApplication is three annotations in one: @Configuration (this class is a
 * bean definition source), @EnableAutoConfiguration (configure beans by inspecting the
 * classpath) and @ComponentScan (scan this package downwards). The scan root is why the
 * application class sits at the top of the package tree - it is the anchor, not just a
 * main method.
 */
@SpringBootApplication
public class MarketDataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketDataServiceApplication.class, args);
    }
}
