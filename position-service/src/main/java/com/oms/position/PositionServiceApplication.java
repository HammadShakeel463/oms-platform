package com.oms.position;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * position-service: consumes fills and derives positions, realised P and L (average cost) and unrealised P and L (marked to the latest tick).
 *
 * <p>@SpringBootApplication is three annotations in one: @Configuration (this class is a
 * bean definition source), @EnableAutoConfiguration (configure beans by inspecting the
 * classpath) and @ComponentScan (scan this package downwards). The scan root is why the
 * application class sits at the top of the package tree - it is the anchor, not just a
 * main method.
 */
@SpringBootApplication
public class PositionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PositionServiceApplication.class, args);
    }
}
