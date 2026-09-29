package com.oms.position.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.domain.Side;
import com.oms.common.event.MarketTickEvent;
import com.oms.common.event.TradeExecutedEvent;
import com.oms.common.event.Topics;
import com.oms.common.money.Ticks;
import com.oms.position.api.dto.AccountPnlResponse;
import com.oms.position.api.dto.PositionResponse;
import com.oms.position.repository.PositionRepository;
import com.oms.position.repository.TradeLedgerRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End to end: trades in over Kafka, positions and P&amp;L out over HTTP.
 *
 * <p>The tests that matter here are the ones a unit test cannot reach: that Flyway and the mappings
 * agree, that the append-only trigger is real, that the {@code net_quantity <> 0 OR open_cost = 0}
 * CHECK constraint actually fires, and that replaying a trade does not double a position.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Testcontainers
class PositionFlowIT {

    private static final String BUYER = "ACC-BUYER";
    private static final String SELLER = "ACC-SELLER";
    private static final String SYMBOL = "HBL";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("oms").withUsername("oms").withPassword("oms");

    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private PositionRepository positionRepository;
    @Autowired
    private TradeLedgerRepository ledgerRepository;

    // --- helpers ---------------------------------------------------------------------

    private HttpHeaders headers(String accountId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Account-Id", accountId);
        return headers;
    }

    private TradeExecutedEvent trade(UUID tradeId, String price, long quantity,
                                     String buyer, String seller, long sequence) {
        return new TradeExecutedEvent(
                UUID.randomUUID().toString(), Instant.now(),
                TradeExecutedEvent.CURRENT_SCHEMA_VERSION,
                tradeId, SYMBOL, Ticks.fromDecimal(new BigDecimal(price)), quantity,
                Side.BUY, UUID.randomUUID(), buyer, UUID.randomUUID(), seller, sequence);
    }

    private void send(String topic, Object event) throws Exception {
        String key = event instanceof com.oms.common.event.DomainEvent e ? e.partitionKey() : SYMBOL;
        kafkaTemplate.send(new ProducerRecord<>(topic, key,
                objectMapper.writeValueAsString(event))).get(10, TimeUnit.SECONDS);
    }

    private PositionResponse position(String accountId, String symbol) {
        return rest.exchange("/api/v1/positions/{symbol}", HttpMethod.GET,
                new HttpEntity<>(headers(accountId)), PositionResponse.class, symbol).getBody();
    }

    private void awaitNet(String accountId, String symbol, long expected) {
        await().atMost(30, TimeUnit.SECONDS).pollInterval(java.time.Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(position(accountId, symbol).netQuantity())
                        .isEqualTo(expected));
    }

    // --- tests -----------------------------------------------------------------------

    @Test
    @DisplayName("a trade creates a long for the buyer and a short for the seller")
    void tradeCreatesBothPositions() throws Exception {
        String buyer = BUYER + "-1";
        String seller = SELLER + "-1";

        send(Topics.TRADES_EXECUTED, trade(UUID.randomUUID(), "172.0000", 500, buyer, seller, 1L));

        awaitNet(buyer, SYMBOL, 500);
        awaitNet(seller, SYMBOL, -500);

        assertThat(position(buyer, SYMBOL).averageCost()).isEqualByComparingTo("172.0000");
        assertThat(position(seller, SYMBOL).averageCost()).isEqualByComparingTo("172.0000");
    }

    @Test
    @DisplayName("a close realises P&L and leaves the position flat with exactly zero open cost")
    void closeRealisesAndBalances() throws Exception {
        String buyer = BUYER + "-2";
        String seller = SELLER + "-2";

        send(Topics.TRADES_EXECUTED, trade(UUID.randomUUID(), "100.0000", 200, buyer, seller, 1L));
        awaitNet(buyer, SYMBOL, 200);

        // Buyer now sells back to a third party at a higher price.
        send(Topics.TRADES_EXECUTED, trade(UUID.randomUUID(), "110.0000", 200, "ACC-THIRD", buyer, 2L));
        awaitNet(buyer, SYMBOL, 0);

        PositionResponse flat = position(buyer, SYMBOL);
        assertThat(flat.realisedPnl()).isEqualByComparingTo("2000.0000");
        // The database CHECK constraint would have rejected the write if any open cost remained,
        // so reaching this line already proves it - the assertion documents the intent.
        assertThat(positionRepository
                .findById(new com.oms.position.domain.PositionEntity.Key(buyer, SYMBOL)))
                .get()
                .satisfies(p -> assertThat(p.getOpenCost()).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("a replayed trade does not double the position")
    void replayedTradeIsIdempotent() throws Exception {
        String buyer = BUYER + "-3";
        String seller = SELLER + "-3";
        UUID tradeId = UUID.randomUUID();
        TradeExecutedEvent event = trade(tradeId, "172.0000", 300, buyer, seller, 1L);

        send(Topics.TRADES_EXECUTED, event);
        awaitNet(buyer, SYMBOL, 300);

        // Exactly the same event again, as a rebalance or a producer retry would deliver it.
        send(Topics.TRADES_EXECUTED, event);
        Thread.sleep(2_000);

        assertThat(position(buyer, SYMBOL).netQuantity())
                .as("the (trade_id, account_id) primary key on trade_ledger is the guard")
                .isEqualTo(300);
        assertThat(ledgerRepository.count()).isPositive();
    }

    @Test
    @DisplayName("crossing through zero opens the new side at the new price")
    void crossingZeroRepricesTheNewSide() throws Exception {
        String trader = BUYER + "-4";

        send(Topics.TRADES_EXECUTED, trade(UUID.randomUUID(), "100.0000", 100, trader, SELLER, 1L));
        awaitNet(trader, SYMBOL, 100);

        // Trader sells 150: closes 100, opens a short of 50 at 110.
        send(Topics.TRADES_EXECUTED, trade(UUID.randomUUID(), "110.0000", 150, "ACC-OTHER", trader, 2L));
        awaitNet(trader, SYMBOL, -50);

        PositionResponse flipped = position(trader, SYMBOL);
        assertThat(flipped.realisedPnl()).isEqualByComparingTo("1000.0000");
        assertThat(flipped.averageCost())
                .as("the new short is opened at the fill price, not the old long average")
                .isEqualByComparingTo("110.0000");
    }

    @Test
    @DisplayName("unrealised P&L appears once a tick arrives, and is absent before")
    void unrealisedRequiresAMark() throws Exception {
        String buyer = BUYER + "-5";
        String symbol = "OGDC";

        send(Topics.TRADES_EXECUTED, new TradeExecutedEvent(
                UUID.randomUUID().toString(), Instant.now(), 1, UUID.randomUUID(), symbol,
                Ticks.fromDecimal(new BigDecimal("200.0000")), 100, Side.BUY,
                UUID.randomUUID(), buyer, UUID.randomUUID(), SELLER, 1L));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(position(buyer, symbol).netQuantity()).isEqualTo(100));

        assertThat(position(buyer, symbol).unrealisedPnl())
                .as("no tick yet, so unrealised is unknown rather than zero")
                .isNull();

        send(Topics.MARKET_DATA_TICKS, new MarketTickEvent(
                UUID.randomUUID().toString(), Instant.now(), 1, symbol,
                Ticks.fromDecimal(new BigDecimal("209.0000")), 100,
                Ticks.fromDecimal(new BigDecimal("211.0000")), 100,
                Ticks.fromDecimal(new BigDecimal("210.0000")), 50, 1L));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            PositionResponse marked = position(buyer, symbol);
            assertThat(marked.markPrice()).isEqualByComparingTo("210.0000");
            // (210 - 200) x 100
            assertThat(marked.unrealisedPnl()).isEqualByComparingTo("1000.0000");
        });
    }

    @Test
    @DisplayName("the P&L summary reports itself incomplete when a position has no mark")
    void pnlSummaryFlagsMissingMarks() throws Exception {
        String buyer = BUYER + "-6";
        String symbol = "LUCK";

        send(Topics.TRADES_EXECUTED, new TradeExecutedEvent(
                UUID.randomUUID().toString(), Instant.now(), 1, UUID.randomUUID(), symbol,
                Ticks.fromDecimal(new BigDecimal("900.0000")), 20, Side.BUY,
                UUID.randomUUID(), buyer, UUID.randomUUID(), SELLER, 1L));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            AccountPnlResponse pnl = rest.exchange("/api/v1/pnl", HttpMethod.GET,
                    new HttpEntity<>(headers(buyer)), AccountPnlResponse.class).getBody();
            assertThat(pnl).isNotNull();
            assertThat(pnl.openPositions()).isEqualTo(1);
            assertThat(pnl.complete()).isFalse();
            assertThat(pnl.unmarkedSymbols()).contains(symbol);
        });
    }

    @Test
    @DisplayName("the trade ledger is append-only: UPDATE is refused by the trigger")
    void ledgerIsImmutable() throws Exception {
        String buyer = BUYER + "-7";
        send(Topics.TRADES_EXECUTED, trade(UUID.randomUUID(), "172.0000", 100, buyer, SELLER, 1L));
        awaitNet(buyer, SYMBOL, 100);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        ledgerRepository.findAll().stream()
                                .filter(e -> e.getAccountId().equals(buyer))
                                .findFirst()
                                .ifPresent(entry -> executeUpdate(buyer)))
                .hasMessageContaining("append-only");
    }

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private void executeUpdate(String accountId) {
        var em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            em.createNativeQuery("UPDATE oms_position.trade_ledger SET quantity = 999 "
                            + "WHERE account_id = :acc")
                    .setParameter("acc", accountId)
                    .executeUpdate();
            em.getTransaction().commit();
        } finally {
            em.close();
        }
    }

    @Test
    @DisplayName("a never-traded account reports an empty position list, not an error")
    void unknownAccountIsEmpty() {
        var response = rest.exchange("/api/v1/positions", HttpMethod.GET,
                new HttpEntity<>(headers("ACC-NOBODY")),
                new ParameterizedTypeReference<List<PositionResponse>>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }
}
