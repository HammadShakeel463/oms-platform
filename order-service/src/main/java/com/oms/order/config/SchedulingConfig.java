package com.oms.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled processing, which drives the outbox publisher.
 *
 * <p>Kept as its own class rather than added to the application class: a test that wants to
 * drive the publisher by hand can exclude this one configuration instead of disabling
 * scheduling globally.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
