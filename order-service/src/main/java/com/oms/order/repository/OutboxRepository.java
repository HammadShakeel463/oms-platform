package com.oms.order.repository;

import com.oms.order.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of unpublished events for this poller instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is the load-bearing part and the reason this is
     * native SQL rather than JPQL. With several order-service replicas polling the same
     * table:
     *
     * <ul>
     *   <li>plain {@code SELECT} - every replica reads the same rows and publishes each
     *       event N times;</li>
     *   <li>{@code FOR UPDATE} - replicas serialise behind each other and throughput is
     *       that of a single poller;</li>
     *   <li>{@code FOR UPDATE SKIP LOCKED} - each replica takes a disjoint batch and they
     *       run in parallel.</li>
     * </ul>
     *
     * <p>It is the database giving you a concurrent work queue with no coordination
     * service, and it is the same idea as a lock-free queue handing each consumer a
     * distinct slot - except the arbitration lives in PostgreSQL rather than in a CAS loop.
     *
     * <p>The row lock is held until the surrounding transaction commits, which is why the
     * publisher marks rows published inside that same transaction.
     */
    @Query(value = """
            SELECT * FROM oms_order.outbox
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublishedBatch(@Param("batchSize") int batchSize);

    long countByPublishedAtIsNull();

    /**
     * Housekeeping: published rows are kept briefly for forensics, then removed. Without
     * this the outbox grows without bound and the partial index stops being cheap.
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
