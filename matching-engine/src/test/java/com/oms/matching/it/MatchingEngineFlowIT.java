package com.oms.matching.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.event.CancelReason;
import com.oms.common.event.OrderAcceptedEvent;
import com.oms.common.event.OrderCancelConfirmedEvent;
import com.oms.common.event.OrderCancelRequestedEvent;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.event.Topics;
import com.oms.common.money.Ticks;
import com.oms.matching.api.BookDepthResponse;
import com.oms.matching.engine.TradeIds;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import com.oms.web.security.OmsRoles;
import com.oms.web.testsupport.TestJwt;
import com.oms.web.testsupport.TestSecurityConfig;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End to end through the real thing: orders in over Kafka, trades out over Kafka, depth
 * queryable over HTTP while it happens.
 *
 * <p>Every unit test in this module could pass while this one fails - the batch listeners, the
 * deserialiser configuration, the send-then-commit ordering and the snapshot publication are all
 * wiring, and wiring is what a unit test cannot reach.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfig.class)
@Testcontainers
class MatchingEngineFlowIT {

    private static final String SYMBOL = "HBL";

    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    static {
        KAFKA.start();
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TestRestTemplate rest;

    private Consumer<String, String> tradeConsumer;
    private Consumer<String, String> reportConsumer;

    /**
     * Book depth is ADMIN-only: it reveals every resting order. A TRADER token would correctly
     * get 403 here, which is asserted separately in the gateway tests.
     */
    @BeforeEach
    void authenticate() {
        var interceptors = rest.getRestTemplate().getInterceptors();
        if (interceptors.isEmpty()) {
            interceptors.add((request, body, execution) -> {
                request.getHeaders().set(org.springframework.http.HttpHeaders.AUTHORIZATION,
                        TestJwt.bearer("ACC-ADMIN", "integration-test", OmsRoles.ADMIN));
                return execution.execute(request, body);
            });
        }
    }

    @BeforeEach
    void subscribe() {
        tradeConsumer = consumerFor(Topics.TRADES_EXECUTED);
        reportConsumer = consumerFor(Topics.EXECUTION_REPORTS);
    }

    private Consumer<String, String> consumerFor(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(
                KAFKA.getBootstrapServers(), "it-" + UUID.randomUUID(), "true");
        Consumer<String, String> consumer = new KafkaConsumer<>(
                props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        consumer.poll(Duration.ofMillis(500));   // force assignment
        return consumer;
    }

    @AfterEach
    void close() {
        if (tradeConsumer != null) {
            tradeConsumer.close();
        }
        if (reportConsumer != null) {
            reportConsumer.close();
        }
    }

    @Test
    @DisplayName("two crossing orders produce a trade at the resting price")
    void crossingOrdersTrade() throws Exception {
        UUID sellId = UUID.randomUUID();
        UUID buyId = UUID.randomUUID();

        send(accepted(sellId, Side.SELL, 1_000, "172.0000"));
        send(accepted(buyId, Side.BUY, 1_000, "173.0000"));

        List<TradeExecutedEvent> trades = awaitTrades(1);

        TradeExecutedEvent trade = trades.get(0);
        assertThat(trade.priceTicks())
                .as("the resting order set the price; improvement accrues to the aggressor")
                .isEqualTo(Ticks.fromDecimal(new BigDecimal("172.0000")));
        assertThat(trade.quantity()).isEqualTo(1_000);
        assertThat(trade.buyOrderId()).isEqualTo(buyId);
        assertThat(trade.sellOrderId()).isEqualTo(sellId);
        assertThat(trade.aggressor()).isEqualTo(Side.BUY);
    }

    @Test
    @DisplayName("the trade id is derived from (symbol, sequence), so a replay is harmless")
    void tradeIdIsDeterministic() throws Exception {
        send(accepted(UUID.randomUUID(), Side.SELL, 500, "172.0000"));
        send(accepted(UUID.randomUUID(), Side.BUY, 500, "172.0000"));

        TradeExecutedEvent trade = awaitTrades(1).get(0);

        assertThat(trade.tradeId())
                .as("a rebuilt book re-emits this exact id, which is what makes "
                        + "order-service deduplication work after a restart")
                .isEqualTo(TradeIds.of(trade.symbol(), trade.sequence()));
    }

    @Test
    @DisplayName("a resting order appears in the depth snapshot over HTTP")
    void depthEndpointReflectsTheBook() throws Exception {
        send(accepted(UUID.randomUUID(), Side.BUY, 700, "171.5000"));

        await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    BookDepthResponse depth = rest.getForObject(
                            "/api/v1/books/{symbol}", BookDepthResponse.class, SYMBOL);
                    assertThat(depth).isNotNull();
                    assertThat(depth.bids()).isNotEmpty();
                    assertThat(depth.bestBid()).isEqualByComparingTo("171.5000");
                    assertThat(depth.bids().get(0).quantity()).isEqualTo(700);
                });
    }

    @Test
    @DisplayName("a cancel request removes the order and a confirmation comes back")
    void cancelIsConfirmed() throws Exception {
        UUID orderId = UUID.randomUUID();
        send(accepted(orderId, Side.BUY, 400, "170.0000"));

        // Wait for it to be on the book before cancelling, so the test is not racing the engine.
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            BookDepthResponse depth = rest.getForObject(
                    "/api/v1/books/{symbol}", BookDepthResponse.class, SYMBOL);
            assertThat(depth.bids()).anySatisfy(level ->
                    assertThat(level.price()).isEqualByComparingTo("170.0000"));
        });

        kafkaTemplate.send(new ProducerRecord<>(Topics.ORDERS_CANCEL_REQUESTS, SYMBOL,
                objectMapper.writeValueAsString(new OrderCancelRequestedEvent(
                        UUID.randomUUID().toString(), Instant.now(),
                        OrderCancelRequestedEvent.CURRENT_SCHEMA_VERSION,
                        orderId, SYMBOL, "ACC-A", "it")))).get(10, TimeUnit.SECONDS);

        List<OrderCancelConfirmedEvent> reports = awaitReports(1);

        assertThat(reports).anySatisfy(report -> {
            assertThat(report.orderId()).isEqualTo(orderId);
            assertThat(report.reason()).isEqualTo(CancelReason.USER_REQUEST);
            assertThat(report.cancelledQuantity()).isEqualTo(400);
        });
    }

    @Test
    @DisplayName("a market order with no contra side is reported as an IOC residual")
    void marketOrderWithNoLiquidity() throws Exception {
        UUID orderId = UUID.randomUUID();
        kafkaTemplate.send(new ProducerRecord<>(Topics.ORDERS_ACCEPTED, "LUCK",
                objectMapper.writeValueAsString(new OrderAcceptedEvent(
                        UUID.randomUUID().toString(), Instant.now(),
                        OrderAcceptedEvent.CURRENT_SCHEMA_VERSION,
                        orderId, "cl-mkt", "ACC-A", "LUCK", Side.BUY,
                        OrderType.MARKET, TimeInForce.IOC, Ticks.NO_PRICE, 300))))
                .get(10, TimeUnit.SECONDS);

        List<OrderCancelConfirmedEvent> reports = awaitReports(1);

        assertThat(reports).anySatisfy(report -> {
            assertThat(report.orderId()).isEqualTo(orderId);
            assertThat(report.reason()).isEqualTo(CancelReason.IOC_RESIDUAL);
            assertThat(report.cancelledQuantity()).isEqualTo(300);
        });
    }

    @Test
    @DisplayName("a replayed accepted event does not double-fill")
    void replayedOrderIsIgnored() throws Exception {
        UUID sellId = UUID.randomUUID();
        UUID buyId = UUID.randomUUID();

        send(accepted(sellId, Side.SELL, 2_000, "172.0000"));
        OrderAcceptedEvent buy = accepted(buyId, Side.BUY, 600, "172.0000");
        send(buy);
        awaitTrades(1);

        // Exactly the same event again, as a broker retry or a consumer restart would deliver it.
        send(buy);
        Thread.sleep(2_000);

        long totalTraded = drain(tradeConsumer, TradeExecutedEvent.class).stream()
                .mapToLong(TradeExecutedEvent::quantity).sum();
        assertThat(totalTraded)
                .as("the engine's bounded dedupe window must suppress the duplicate")
                .isZero();
    }

    // --- helpers ---------------------------------------------------------------------

    private OrderAcceptedEvent accepted(UUID orderId, Side side, long quantity, String price) {
        return new OrderAcceptedEvent(
                UUID.randomUUID().toString(), Instant.now(),
                OrderAcceptedEvent.CURRENT_SCHEMA_VERSION,
                orderId, "cl-" + orderId, "ACC-A", SYMBOL, side,
                OrderType.LIMIT, TimeInForce.DAY,
                Ticks.fromDecimal(new BigDecimal(price)), quantity);
    }

    private void send(OrderAcceptedEvent event) throws Exception {
        kafkaTemplate.send(new ProducerRecord<>(Topics.ORDERS_ACCEPTED, event.partitionKey(),
                objectMapper.writeValueAsString(event))).get(10, TimeUnit.SECONDS);
    }

    private List<TradeExecutedEvent> awaitTrades(int atLeast) {
        List<TradeExecutedEvent> collected = new ArrayList<>();
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            collected.addAll(drain(tradeConsumer, TradeExecutedEvent.class));
            assertThat(collected).hasSizeGreaterThanOrEqualTo(atLeast);
        });
        return collected;
    }

    private List<OrderCancelConfirmedEvent> awaitReports(int atLeast) {
        List<OrderCancelConfirmedEvent> collected = new ArrayList<>();
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            collected.addAll(drain(reportConsumer, OrderCancelConfirmedEvent.class));
            assertThat(collected).hasSizeGreaterThanOrEqualTo(atLeast);
        });
        return collected;
    }

    private <T> List<T> drain(Consumer<String, String> consumer, Class<T> type) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        List<T> events = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                events.add(objectMapper.readValue(record.value(), type));
            } catch (Exception e) {
                throw new IllegalStateException("Unreadable event on " + record.topic(), e);
            }
        }
        return events;
    }
}
