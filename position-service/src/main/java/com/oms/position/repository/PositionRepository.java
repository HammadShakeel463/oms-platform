package com.oms.position.repository;

import com.oms.position.domain.PositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PositionRepository extends JpaRepository<PositionEntity, PositionEntity.Key> {

    List<PositionEntity> findByIdAccountIdOrderByIdSymbolAsc(String accountId);

    /** Open positions only - the query a risk report actually wants. */
    List<PositionEntity> findByIdAccountIdAndNetQuantityNotOrderByIdSymbolAsc(
            String accountId, long netQuantity);
}
