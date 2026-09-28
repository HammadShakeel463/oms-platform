package com.oms.order.repository;

import com.oms.common.domain.OrderStatus;
import com.oms.order.domain.OrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA writes the implementation of this interface at startup.
 *
 * <p>For a C++ reader this is the least obvious thing in the framework: there is no class
 * implementing {@code OrderRepository} anywhere in the source tree. Spring builds a proxy
 * at runtime and derives the SQL from the method <em>name</em> -
 * {@code findByAccountIdAndSymbol} parses into a WHERE clause. That is why method names
 * here look verbose: the name is the query.
 *
 * <p>Derived names stop being readable past two or three predicates, which is where
 * {@code @Query} takes over - as below. The rule applied in this project: derive it if the
 * name stays obvious, write JPQL if it does not, and drop to native SQL only for things
 * JPQL genuinely cannot express (see {@link OutboxRepository}).
 */
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    /** Idempotency probe for order placement. */
    boolean existsByAccountIdAndClientOrderId(String accountId, String clientOrderId);

    Optional<OrderEntity> findByAccountIdAndClientOrderId(String accountId, String clientOrderId);

    /**
     * Fetch scoped to the account. The account id is part of the query rather than checked
     * after loading, so an authorisation mistake cannot leak another account's order -
     * "not yours" and "does not exist" return the same 404, which is also the right answer
     * for information disclosure.
     */
    Optional<OrderEntity> findByOrderIdAndAccountId(UUID orderId, String accountId);

    /**
     * Filtered search. Null parameters mean "no filter", which keeps one query instead of
     * four overloads or a Specification API that is heavier than this problem needs.
     */
    @Query("""
            SELECT o FROM OrderEntity o
            WHERE o.accountId = :accountId
              AND (:symbol IS NULL OR o.symbol = :symbol)
              AND (:status IS NULL OR o.status = :status)
            ORDER BY o.createdAt DESC
            """)
    Page<OrderEntity> search(@Param("accountId") String accountId,
                            @Param("symbol") String symbol,
                            @Param("status") OrderStatus status,
                            Pageable pageable);
}
