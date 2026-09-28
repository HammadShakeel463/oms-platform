package com.oms.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The cheapest high-value test in a Spring project: it starts the whole application
 * context. Most Spring mistakes - a missing bean, an ambiguous injection point, a broken
 * property placeholder, a bad @Value - are wiring mistakes, and wiring is resolved at
 * startup rather than at compile time. This test is what turns those into build failures.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ContextLoadsTest {

    @Test
    @DisplayName("application context starts")
    void contextLoads() {
        // Intentionally empty: a failure to start fails the test.
    }
}
