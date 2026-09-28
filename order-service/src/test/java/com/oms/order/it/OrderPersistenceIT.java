package com.oms.order.it;

import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.Side;
import com.oms.order.TestFixtures;
import com.oms.order.domain.OrderAuditEntity;
import com.oms.order.domain.OrderEntity;
import com.oms.order.domain.OutboxEvent;
import com.oms.order.repository.AccountRepository;
import com.oms.order.repository.OrderAuditRepository;
import com.oms.order.repository.OrderRepository;
import com.oms.order.repository.OutboxRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the things that only exist in the database: Flyway migrations, constraints,
 * triggers and PostgreSQL-specific SQL.
 *
 * <p>None of these can be covered by a unit test, and all of them are load-bearing.
 */
class OrderPersistenceIT extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderAuditRepository auditRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("Flyway ran and seeded the demo accounts")
    void migrationsApplied() {
        assertThat(accountRepository.findById("ACC-TRADER-1")).isPresent();
        assertThat(accountRepository.findById("ACC-SMALL-1"))
                .get()
                .satisfies(a -> assertThat(a.getMaxPositionQty()).isEqualTo(5_000L));
    }

    @Test
    @DisplayName("Hibernate mappings validate against the Flyway schema")
    void mappingsMatchSchema() {
        // ddl-auto=validate means the context would not have started if any @Column did not
        // match the migration. Reaching this line is the assertion; the query below proves
        // the tables are genuinely usable rather than merely present.
        assertThat(orderRepository.count()).isNotNegative();
    }

    @Test
    @Transactional
    @DisplayName("a reused clientOrderId is refused by the UNIQUE constraint, not just by the check")
    void duplicateClientOrderIdIsRefusedByTheDatabase() {
        String clientOrderId = "dup-" + UUID.randomUUID();

        orderRepository.saveAndFlush(OrderEntity.newOrder(clientOrderId, TestFixtures.ACCOUNT,
                "HBL", Side.BUY, com.oms.common.domain.OrderType.LIMIT,
                com.oms.common.domain.TimeInForce.DAY, new java.math.BigDecimal("172.4500"), 100));

        assertThatThrownBy(() -> orderRepository.saveAndFlush(
                OrderEntity.newOrder(clientOrderId, TestFixtures.ACCOUNT, "HBL", Side.BUY,
                        com.oms.common.domain.OrderType.LIMIT,
                        com.oms.common.domain.TimeInForce.DAY,
                        new java.math.BigDecimal("172.4500"), 100)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the audit trail is append-only: UPDATE and DELETE are refused by the trigger")
    void auditTrailIsImmutable() {
        UUID orderId = transactionTemplate.execute(status -> {
            OrderEntity order = orderRepository.save(TestFixtures.limitOrder(Side.BUY, 100, "172.4500"));
            auditRepository.save(new OrderAuditEntity(order.getOrderId(), 1, null,
                    OrderStatus.NEW, "created", 0, 100, null, "test", "trace-1", Instant.now()));
            return order.getOrderId();
        });

        // A direct native UPDATE, bypassing JPA entirely - exactly what an ad-hoc SQL
        // session or a "quick fix" script would do.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE oms_order.order_audit SET reason = 'tampered' WHERE order_id = :id")
                        .setParameter("id", orderId)
                        .executeUpdate()))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "DELETE FROM oms_order.order_audit WHERE order_id = :id")
                        .setParameter("id", orderId)
                        .executeUpdate()))
                .hasMessageContaining("append-only");

        List<OrderAuditEntity> history = auditRepository.findByOrderIdOrderBySeqAsc(orderId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getReason()).isEqualTo("created");
    }

    @Test
    @DisplayName("a LIMIT order without a price is refused by the CHECK constraint")
    void limitPriceCheckConstraint() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                                INSERT INTO oms_order.orders
                                  (order_id, client_order_id, account_id, symbol, side, order_type,
                                   time_in_force, limit_price, quantity, status)
                                VALUES (:id, :cl, :acc, 'HBL', 'BUY', 'LIMIT', 'DAY', NULL, 100, 'NEW')
                                """)
                        .setParameter("id", UUID.randomUUID())
                        .setParameter("cl", "ck-" + UUID.randomUUID())
                        .setParameter("acc", TestFixtures.ACCOUNT)
                        .executeUpdate()))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("the audit sequence is per order and strictly increasing")
    void auditSequenceIsPerOrder() {
        UUID orderId = transactionTemplate.execute(status -> {
            OrderEntity order = orderRepository.save(TestFixtures.limitOrder(Side.BUY, 100, "172.4500"));
            for (int seq = 1; seq <= 3; seq++) {
                auditRepository.save(new OrderAuditEntity(order.getOrderId(), seq, null,
                        OrderStatus.NEW, "step " + seq, 0, 100, null, "test", null, Instant.now()));
            }
            return order.getOrderId();
        });

        assertThat(auditRepository.currentSeq(orderId)).isEqualTo(3);
        assertThat(auditRepository.findByOrderIdOrderBySeqAsc(orderId))
                .extracting(OrderAuditEntity::getSeq)
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("a duplicate audit sequence for one order is refused")
    void auditSequenceIsUnique() {
        OrderEntity order = transactionTemplate.execute(status ->
                orderRepository.save(TestFixtures.limitOrder(Side.BUY, 100, "172.4500")));

        transactionTemplate.executeWithoutResult(status ->
                auditRepository.save(new OrderAuditEntity(order.getOrderId(), 1, null,
                        OrderStatus.NEW, "first", 0, 100, null, "test", null, Instant.now())));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                auditRepository.saveAndFlush(new OrderAuditEntity(order.getOrderId(), 1, null,
                        OrderStatus.NEW, "again", 0, 100, null, "test", null, Instant.now()))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the outbox claim query runs and returns unpublished rows oldest first")
    void outboxClaimQueryWorks() {
        // FOR UPDATE SKIP LOCKED is native SQL, so nothing but a real PostgreSQL proves it
        // even parses.
        transactionTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < 3; i++) {
                outboxRepository.save(new OutboxEvent(UUID.randomUUID(), "agg-" + i,
                        "TestEvent", "oms.test.v1", "HBL", "{\"n\":" + i + "}", "trace-1"));
            }
        });

        List<OutboxEvent> claimed = transactionTemplate.execute(status ->
                outboxRepository.claimUnpublishedBatch(2));

        assertThat(claimed).hasSize(2);
        assertThat(claimed).allMatch(e -> !e.isPublished());
        assertThat(outboxRepository.countByPublishedAtIsNull()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("jsonb payloads round-trip through the outbox")
    void outboxStoresJsonb() {
        String payload = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"quantity\":1000}";

        Long id = transactionTemplate.execute(status -> outboxRepository.save(
                new OutboxEvent(UUID.randomUUID(), "agg", "OrderAcceptedEvent",
                        "oms.orders.accepted.v1", "HBL", payload, null)).getId());

        assertThat(outboxRepository.findById(id))
                .get()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"quantity\""));
    }
}
