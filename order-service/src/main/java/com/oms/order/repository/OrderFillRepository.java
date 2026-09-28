package com.oms.order.repository;

import com.oms.order.domain.OrderFillEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderFillRepository
        extends JpaRepository<OrderFillEntity, OrderFillEntity.Key> {

    List<OrderFillEntity> findByIdOrderIdOrderByEngineSeqAsc(UUID orderId);
}
