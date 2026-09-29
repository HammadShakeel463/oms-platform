package com.oms.marketdata.repository;

import com.oms.common.reference.InstrumentStatus;
import com.oms.marketdata.domain.InstrumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstrumentRepository extends JpaRepository<InstrumentEntity, String> {

    List<InstrumentEntity> findByStatusOrderBySymbolAsc(InstrumentStatus status);

    List<InstrumentEntity> findAllByOrderBySymbolAsc();
}
