package com.oms.order.repository;

import com.oms.order.domain.OrderAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrderAuditRepository extends JpaRepository<OrderAuditEntity, Long> {

    List<OrderAuditEntity> findByOrderIdOrderBySeqAsc(UUID orderId);

    /**
     * Next sequence number for an order. Returns 0 for an order with no history, so the
     * first audit row is seq 1.
     *
     * <p>A read-then-write like this is only safe because writes for one order are
     * serialised: the caller holds the order row under an optimistic lock in the same
     * transaction, so two concurrent transitions cannot both read the same max and commit.
     * The UNIQUE (order_id, seq) constraint is the backstop if that reasoning is ever wrong.
     */
    @Query("SELECT COALESCE(MAX(a.seq), 0) FROM OrderAuditEntity a WHERE a.orderId = :orderId")
    int currentSeq(@Param("orderId") UUID orderId);
}
