package com.oms.position.repository;

import com.oms.position.domain.TradeLedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeLedgerRepository
        extends JpaRepository<TradeLedgerEntry, TradeLedgerEntry.Key> {

    Page<TradeLedgerEntry> findByIdAccountIdOrderByExecutedAtDesc(String accountId, Pageable pageable);

    Page<TradeLedgerEntry> findByIdAccountIdAndSymbolOrderByExecutedAtDesc(
            String accountId, String symbol, Pageable pageable);
}
