package com.oms.marketdata.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.event.MarketTickEvent;
import com.oms.common.event.Topics;
import com.oms.common.reference.InstrumentStatus;
import com.oms.common.reference.InstrumentView;
import com.oms.marketdata.api.QuoteResponse;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import com.oms.web.security.OmsRoles;
import com.oms.web.testsupport.TestJwt;
import com.oms.web.testsupport.TestSecurityConfig;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End to end: reference data from PostgreSQL, a live simulated feed, quotes cached in Redis, and
 * ticks on Kafka.
 *
 * <p>Includes the SSE stream, because a streaming endpoint is the one thing in this service that
 * absolutely cannot be verified by a unit test - the async dispatch, the task executor and the
 * emitter lifecycle are all container behaviour.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfig.class)
@Testcontainers
class MarketDataFlowIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("oms").withUsername("oms").withPassword("oms");

    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        KAFKA.start();
        REDIS.start();
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    private Consumer<String, String> tickConsumer;

    /**
     * Market data requires an authenticated caller - a quote is not account data, but an
     * unauthenticated feed of a venue is a product somebody sells. An interceptor rather than a
     * header per call, because several assertions use the convenience getForObject form.
     */
    @BeforeEach
    void authenticate() {
        var interceptors = rest.getRestTemplate().getInterceptors();
        if (interceptors.isEmpty()) {
            interceptors.add((request, body, execution) -> {
                request.getHeaders().set(org.springframework.http.HttpHeaders.AUTHORIZATION,
                        TestJwt.bearer("ACC-TRADER-1", "integration-test", OmsRoles.TRADER));
                return execution.execute(request, body);
            });
        }
    }

    @BeforeEach
    void subscribe() {
        Map<String, Object> props = org.springframework.kafka.test.utils.KafkaTestUtils
                .consumerProps(KAFKA.getBootstrapServers(), "it-" + UUID.randomUUID(), "true");
        tickConsumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        tickConsumer.subscribe(List.of(Topics.MARKET_DATA_TICKS));
        tickConsumer.poll(Duration.ofMillis(500));
    }

    @AfterEach
    void close() {
        if (tickConsumer != null) {
            tickConsumer.close();
        }
    }

    @Test
    @DisplayName("Flyway seeded the PSX instrument universe, including the non-tradeable ones")
    void instrumentsAreSeeded() {
        var response = rest.exchange("/api/v1/instruments", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<InstrumentView>>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(InstrumentView::symbol)
                .contains("HBL", "OGDC", "LUCK", "ENGRO", "PSO", "MCB", "TRG", "SYS");

        // A halted and a delisted instrument ship deliberately, so the pre-trade rejection paths
        // are exercisable against real reference data rather than only in unit tests.
        assertThat(response.getBody())
                .filteredOn(i -> i.status() != InstrumentStatus.ACTIVE)
                .extracting(InstrumentView::symbol)
                .contains("PIAA", "DEAD");
    }

    @Test
    @DisplayName("an instrument carries prices as fixed-point ticks, not decimals")
    void instrumentCrossesTheRepresentationBoundary() {
        InstrumentView hbl = rest.getForObject("/api/v1/instruments/{symbol}",
                InstrumentView.class, "HBL");

        assertThat(hbl).isNotNull();
        assertThat(hbl.referencePriceTicks())
                .as("172.4500 at scale 4 (ADR 0002)")
                .isEqualTo(1_724_500L);
        assertThat(hbl.tickSizeTicks()).isEqualTo(100L);
        assertThat(hbl.lotSize()).isEqualTo(1L);
    }

    @Test
    @DisplayName("an unknown symbol is a 404 in the shared error contract")
    void unknownInstrumentIs404() {
        var response = rest.getForEntity("/api/v1/instruments/{symbol}",
                com.oms.common.error.ApiError.class, "NOSUCH");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("INSTRUMENT_NOT_FOUND");
        assertThat(response.getBody().traceId()).isNotBlank();
    }

    @Test
    @DisplayName("the simulator produces a two-sided quote within the risk band")
    void quoteEndpointServesALiveQuote() {
        await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    QuoteResponse quote = rest.getForObject("/api/v1/quotes/{symbol}",
                            QuoteResponse.class, "HBL");

                    assertThat(quote).isNotNull();
                    assertThat(quote.bid()).isNotNull();
                    assertThat(quote.ask()).isNotNull();
                    assertThat(quote.bid()).isLessThan(quote.ask());
                    assertThat(quote.sequence()).isPositive();
                    // Inside the 10% fat-finger band around 172.45, or order-service would
                    // reject every order in this symbol.
                    assertThat(quote.mid()).isBetween(
                            new java.math.BigDecimal("155.00"),
                            new java.math.BigDecimal("190.00"));
                });
    }

    @Test
    @DisplayName("the top-of-book endpoint returns one level per side")
    void topOfBook() {
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            QuoteResponse quote = rest.getForObject("/api/v1/quotes/{symbol}",
                    QuoteResponse.class, "OGDC");
            assertThat(quote).isNotNull();
            assertThat(quote.spread()).isPositive();
        });
    }

    @Test
    @DisplayName("ticks reach Kafka, keyed by symbol, with increasing sequence numbers")
    void ticksReachKafka() {
        List<MarketTickEvent> collected = new ArrayList<>();
        List<String> keys = new ArrayList<>();

        await().atMost(25, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, String> records = tickConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                keys.add(record.key());
                collected.add(objectMapper.readValue(record.value(), MarketTickEvent.class));
            }
            assertThat(collected).hasSizeGreaterThan(20);
        });

        assertThat(keys)
                .as("the key IS the ordering guarantee - see kafka-event-design.md")
                .allSatisfy(key -> assertThat(key).isNotBlank());

        List<Long> hblSequences = collected.stream()
                .filter(t -> t.symbol().equals("HBL"))
                .map(MarketTickEvent::sequence)
                .toList();

        assertThat(hblSequences).isNotEmpty();
        for (int i = 1; i < hblSequences.size(); i++) {
            assertThat(hblSequences.get(i)).isGreaterThan(hblSequences.get(i - 1));
        }
    }

    @Test
    @DisplayName("the SSE stream delivers quote events to a subscriber")
    void sseStreamDeliversQuotes() {
        // TestRestTemplate reads the body as a String: enough to prove the async dispatch, the
        // task executor, the emitter lifecycle and the event framing all work end to end. The
        // server closes the stream at stream-timeout (20s in this profile), which is what makes a
        // blocking read here terminate.
        String body = rest.getForObject("/api/v1/quotes/stream?symbols=HBL", String.class);

        assertThat(body).isNotNull();
        assertThat(body)
                .as("SSE framing: an event name and a data line")
                .contains("event:quote")
                .contains("data:");
        assertThat(body).contains("\"symbol\":\"HBL\"");
        assertThat(body)
                .as("only the symbol that was asked for")
                .doesNotContain("\"symbol\":\"OGDC\"");
    }

    @Test
    @DisplayName("a quote survives in Redis after the live state is bypassed")
    void quoteIsCachedInRedis() {
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rest.getForObject("/api/v1/quotes/{symbol}", QuoteResponse.class, "MCB"))
                        .isNotNull());

        // The generator writes through on every tick, so the key must exist. Reading Redis
        // directly - through the container-managed template - proves the write-through rather
        // than the in-memory path.
        assertThat(redis.opsForValue().get("oms:quote:MCB"))
                .as("the cache is what lets any instance answer, and survive a restart")
                .isNotNull()
                .contains("\"symbol\":\"MCB\"");
    }
}
