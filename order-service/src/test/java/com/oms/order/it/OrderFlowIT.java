package com.oms.order.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.error.ApiError;
import com.oms.common.event.OrderAcceptedEvent;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.event.Topics;
import com.oms.common.money.Ticks;
import com.oms.order.api.dto.OrderAuditResponse;
import com.oms.order.api.dto.OrderResponse;
import com.oms.order.api.dto.PlaceOrderRequest;
import com.oms.order.repository.OrderRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.oms.web.security.OmsRoles;
import com.oms.web.testsupport.TestJwt;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

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
 * End-to-end: HTTP in, Kafka out, Kafka in, HTTP out.
 *
 * <p>This is the test that proves the pieces are actually connected - the transactional
 * outbox really publishes, the listener really consumes, the state machine really advances
 * and the audit trail really records it. Every unit test in this module could pass while
 * this one fails, which is exactly why it exists.
 */
class OrderFlowIT extends AbstractIntegrationTest {

    private static final String ACCOUNT = "ACC-TRADER-1";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrderRepository orderRepository;

    private Consumer<String, String> engineConsumer;

    /** Stands in for the matching engine: subscribes to what order-service publishes. */
    @BeforeEach
    void subscribeAsEngine() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(
                KAFKA.getBootstrapServers(), "test-engine-" + UUID.randomUUID(), "true");
        engineConsumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(
                props, new StringDeserializer(), new StringDeserializer());
        engineConsumer.subscribe(List.of(Topics.ORDERS_ACCEPTED));
        engineConsumer.poll(Duration.ofMillis(500));   // force the assignment
    }

    @AfterEach
    void closeConsumer() {
        if (engineConsumer != null) {
            engineConsumer.close();
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // A real signed token, verified by the production validator. The account and the
        // audit actor both come from claims - there is no X-Account-Id header any more.
        headers.set(HttpHeaders.AUTHORIZATION,
                TestJwt.bearer(ACCOUNT, "integration-test", OmsRoles.TRADER));
        return headers;
    }

    private ResponseEntity<OrderResponse> place(PlaceOrderRequest request) {
        return rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(request, headers()), OrderResponse.class);
    }

    private static PlaceOrderRequest limit(Side side, long quantity, String price) {
        return new PlaceOrderRequest("cl-" + UUID.randomUUID(), "HBL", side,
                OrderType.LIMIT, TimeInForce.DAY, new BigDecimal(price), quantity);
    }

    @Test
    @DisplayName("placing an order persists it, routes it, and publishes it to the engine topic")
    void placeOrderReachesKafka() throws Exception {
        ResponseEntity<OrderResponse> response = place(limit(Side.BUY, 1_000, "172.4500"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(OrderStatus.ROUTED);
        assertThat(body.leavesQuantity()).isEqualTo(1_000);

        // The outbox publisher is on a 50ms schedule in this profile.
        OrderAcceptedEvent published = awaitAcceptedEvent(body.orderId());

        assertThat(published.limitPriceTicks()).isEqualTo(1_724_500L);
        assertThat(published.quantity()).isEqualTo(1_000);
        assertThat(published.accountId()).isEqualTo(ACCOUNT);
    }

    @Test
    @DisplayName("a fill from the engine drives the order to FILLED with a complete audit trail")
    void fillFromEngineCompletesTheOrder() throws Exception {
        UUID buyOrderId = place(limit(Side.BUY, 1_000, "173.0000")).getBody().orderId();
        UUID sellOrderId = place(limit(Side.SELL, 1_000, "172.0000")).getBody().orderId();

        awaitAcceptedEvent(buyOrderId);
        awaitAcceptedEvent(sellOrderId);

        publishTrade(buyOrderId, sellOrderId, "172.5000", 400, 1L);
        awaitStatus(buyOrderId, OrderStatus.PARTIALLY_FILLED);

        publishTrade(buyOrderId, sellOrderId, "172.5000", 600, 2L);
        awaitStatus(buyOrderId, OrderStatus.FILLED);

        OrderResponse filled = getOrder(buyOrderId);
        assertThat(filled.filledQuantity()).isEqualTo(1_000);
        assertThat(filled.leavesQuantity()).isZero();
        assertThat(filled.avgPrice()).isEqualByComparingTo("172.5000");

        List<OrderAuditResponse> audit = auditTrail(buyOrderId);
        assertThat(audit).extracting(OrderAuditResponse::newStatus)
                .containsExactly(OrderStatus.NEW, OrderStatus.VALIDATED, OrderStatus.ROUTED,
                        OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED);
        assertThat(audit).extracting(OrderAuditResponse::seq).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("a replayed trade does not double-fill the order")
    void replayedTradeIsIdempotent() throws Exception {
        UUID buyOrderId = place(limit(Side.BUY, 1_000, "173.0000")).getBody().orderId();
        UUID sellOrderId = place(limit(Side.SELL, 1_000, "172.0000")).getBody().orderId();
        awaitAcceptedEvent(buyOrderId);
        awaitAcceptedEvent(sellOrderId);

        TradeExecutedEvent trade = trade(buyOrderId, sellOrderId, "172.5000", 400, 1L);
        send(Topics.TRADES_EXECUTED, trade);
        awaitStatus(buyOrderId, OrderStatus.PARTIALLY_FILLED);

        // Exactly the same event again - the same tradeId - as a broker replay would.
        send(Topics.TRADES_EXECUTED, trade);

        // Nothing to await on, so give the listener room to have got it wrong.
        Thread.sleep(1_000);
        assertThat(getOrder(buyOrderId).filledQuantity())
                .as("the (tradeId, orderId) primary key makes the replay a no-op")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("a risk rejection returns 422 AND leaves a permanent REJECTED record")
    void riskRejectionIsRecorded() {
        // ACC-TRADER-1 has a 5,000,000 notional limit: 40,000 x 172.45 = 6,898,000.
        ResponseEntity<ApiError> response = rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(limit(Side.BUY, 40_000, "172.4500"), headers()), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("RISK_LIMIT_BREACHED");

        // The point of noRollbackFor: the rejection survived the exception.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(orderRepository.findAll())
                        .anySatisfy(order -> {
                            assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
                            assertThat(order.getRejectReason()).contains("MAX_ORDER_VALUE");
                        }));
    }

    @Test
    @DisplayName("a fat-finger price is rejected before it reaches the engine")
    void fatFingerNeverReachesKafka() throws Exception {
        ResponseEntity<ApiError> response = rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(limit(Side.BUY, 100, "1724.5000"), headers()), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().message()).contains("outside the permitted band");

        Thread.sleep(500);
        assertThat(drain()).noneSatisfy(event ->
                assertThat(event.limitPriceTicks()).isEqualTo(17_245_000L));
    }

    @Test
    @DisplayName("a reused clientOrderId is refused with 409")
    void duplicateClientOrderIdIsRefused() {
        PlaceOrderRequest request = limit(Side.BUY, 100, "172.4500");
        assertThat(place(request).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<ApiError> second = rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(request, headers()), ApiError.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().code()).isEqualTo("DUPLICATE_CLIENT_ORDER_ID");
    }

    @Test
    @DisplayName("cancelling returns 202 and the order stays live until the engine confirms")
    void cancelIsPendingUntilConfirmed() throws Exception {
        UUID orderId = place(limit(Side.BUY, 1_000, "172.4500")).getBody().orderId();
        awaitAcceptedEvent(orderId);

        ResponseEntity<OrderResponse> cancel = rest.exchange("/api/v1/orders/{id}",
                HttpMethod.DELETE, new HttpEntity<>(headers()), OrderResponse.class, orderId);

        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(cancel.getBody().status()).isEqualTo(OrderStatus.ROUTED);

        // Now the engine confirms, and only now does it become CANCELLED.
        send(Topics.EXECUTION_REPORTS, new com.oms.common.event.OrderCancelConfirmedEvent(
                UUID.randomUUID().toString(), Instant.now(), 1, orderId, "HBL", ACCOUNT,
                com.oms.common.event.CancelReason.USER_REQUEST, 1_000, 1L));

        awaitStatus(orderId, OrderStatus.CANCELLED);
    }

    // --- helpers ---------------------------------------------------------------------

    private OrderResponse getOrder(UUID orderId) {
        return rest.exchange("/api/v1/orders/{id}", HttpMethod.GET,
                new HttpEntity<>(headers()), OrderResponse.class, orderId).getBody();
    }

    private List<OrderAuditResponse> auditTrail(UUID orderId) {
        return rest.exchange("/api/v1/orders/{id}/audit", HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<List<OrderAuditResponse>>() {
                }, orderId).getBody();
    }

    private void awaitStatus(UUID orderId, OrderStatus expected) {
        await().atMost(15, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(getOrder(orderId).status()).isEqualTo(expected));
    }

    private void send(String topic, Object event) throws Exception {
        String key = event instanceof com.oms.common.event.DomainEvent e ? e.partitionKey() : "HBL";
        kafkaTemplate.send(new ProducerRecord<>(topic, key, objectMapper.writeValueAsString(event)))
                .get(10, TimeUnit.SECONDS);
    }

    private void publishTrade(UUID buyOrderId, UUID sellOrderId, String price,
                              long quantity, long sequence) throws Exception {
        send(Topics.TRADES_EXECUTED, trade(buyOrderId, sellOrderId, price, quantity, sequence));
    }

    private static TradeExecutedEvent trade(UUID buyOrderId, UUID sellOrderId, String price,
                                            long quantity, long sequence) {
        return new TradeExecutedEvent(UUID.randomUUID().toString(), Instant.now(),
                TradeExecutedEvent.CURRENT_SCHEMA_VERSION, UUID.randomUUID(), "HBL",
                Ticks.fromDecimal(new BigDecimal(price)), quantity, Side.BUY,
                buyOrderId, ACCOUNT, sellOrderId, ACCOUNT, sequence);
    }

    /** Polls the accepted topic until the event for this order shows up. */
    private OrderAcceptedEvent awaitAcceptedEvent(UUID orderId) {
        List<OrderAcceptedEvent> seen = new ArrayList<>();
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            seen.addAll(drain());
            assertThat(seen).anyMatch(e -> e.orderId().equals(orderId));
        });
        return seen.stream().filter(e -> e.orderId().equals(orderId)).findFirst().orElseThrow();
    }

    private List<OrderAcceptedEvent> drain() {
        ConsumerRecords<String, String> records = engineConsumer.poll(Duration.ofMillis(500));
        List<OrderAcceptedEvent> events = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                events.add(objectMapper.readValue(record.value(), OrderAcceptedEvent.class));
            } catch (Exception e) {
                throw new IllegalStateException("Unreadable event on " + record.topic(), e);
            }
        }
        return events;
    }
}
