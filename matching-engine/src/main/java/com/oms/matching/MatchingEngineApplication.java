package com.oms.matching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * matching-engine: a price-time priority limit order book held entirely in memory. The only stateful hot path in the platform, and the one place where allocation and lock contention are treated as correctness problems rather than performance nits.
 *
 * <p>@SpringBootApplication is three annotations in one: @Configuration (this class is a
 * bean definition source), @EnableAutoConfiguration (configure beans by inspecting the
 * classpath) and @ComponentScan (scan this package downwards). The scan root is why the
 * application class sits at the top of the package tree - it is the anchor, not just a
 * main method.
 */
@SpringBootApplication
public class MatchingEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchingEngineApplication.class, args);
    }
}
