package com.oms.order.repository;

import com.oms.order.domain.PositionExposureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionExposureRepository
        extends JpaRepository<PositionExposureEntity, PositionExposureEntity.Key> {
}
