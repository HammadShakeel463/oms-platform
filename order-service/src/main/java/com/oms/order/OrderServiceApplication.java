package com.oms.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * order-service: the write side of the order lifecycle. Every order is born here, validated here, risk-checked here, and every state change is journalled here before anyone else hears about it.
 *
 * <p>@SpringBootApplication is three annotations in one: @Configuration (this class is a
 * bean definition source), @EnableAutoConfiguration (configure beans by inspecting the
 * classpath) and @ComponentScan (scan this package downwards). The scan root is why the
 * application class sits at the top of the package tree - it is the anchor, not just a
 * main method.
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
