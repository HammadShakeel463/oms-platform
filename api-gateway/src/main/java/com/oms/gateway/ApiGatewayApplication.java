package com.oms.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * api-gateway: the only port exposed outside the cluster. Routes to the services, validates JWTs, enforces roles and rate-limits per principal.
 *
 * <p>@SpringBootApplication is three annotations in one: @Configuration (this class is a
 * bean definition source), @EnableAutoConfiguration (configure beans by inspecting the
 * classpath) and @ComponentScan (scan this package downwards). The scan root is why the
 * application class sits at the top of the package tree - it is the anchor, not just a
 * main method.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
