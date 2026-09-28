package com.oms.order.it;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests: real PostgreSQL, real Kafka, real Redis, in containers.
 *
 * <p><b>Why not H2 and an embedded broker.</b> An in-memory database is a different database.
 * H2 would not run the {@code FOR UPDATE SKIP LOCKED} query, would not enforce the plpgsql
 * append-only trigger, would not have {@code jsonb}, and would happily accept a migration
 * that PostgreSQL rejects. A test suite that passes against a database you do not deploy is
 * testing the wrong thing. Containers cost seconds of startup and buy the property that
 * green means green.
 *
 * <p><b>Static containers, started once.</b> JUnit starts them on first use and Testcontainers
 * reuses them for every test class that extends this one, because the fields are static and
 * the Spring context is cached across classes with the same configuration. Per-test
 * containers would be correct and unusably slow.
 *
 * <p><b>{@code @ServiceConnection}</b> (Boot 3.1+) replaces the {@code @DynamicPropertySource}
 * block that every older tutorial shows. Boot reads the container and configures the
 * datasource, the Kafka bootstrap servers and the Redis connection itself - no property
 * names to spell correctly, and no drift when a property is renamed upstream.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("oms")
                    .withUsername("oms")
                    .withPassword("oms");

    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        // Started explicitly rather than through @Container so that the JVM keeps them for
        // the whole test run instead of stopping them after each class. Ryuk removes them
        // when the JVM exits.
        POSTGRES.start();
        KAFKA.start();
        REDIS.start();
    }
}
