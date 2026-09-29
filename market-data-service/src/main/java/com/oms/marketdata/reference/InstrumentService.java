package com.oms.marketdata.reference;

import com.oms.common.error.NotFoundException;
import com.oms.common.reference.InstrumentStatus;
import com.oms.common.reference.InstrumentView;
import com.oms.marketdata.domain.InstrumentEntity;
import com.oms.marketdata.repository.InstrumentRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Serves reference data.
 *
 * <p>Cached in Redis even though this service owns the table. The reason is not its own read
 * latency - a single-row primary-key lookup on a table of a few hundred rows is nothing. It is
 * that order-service calls this endpoint on the order path, so the cache here plus the cache
 * in order-service means a reference-data lookup crosses neither the network nor the database
 * in the common case.
 *
 * <p>{@code @Cacheable} on a method called from another method of this same class would not be
 * cached at all - the proxy only intercepts calls arriving from outside the bean. Both cached
 * methods here are entry points, called from controllers, which is why they work.
 */
@Service
public class InstrumentService {

    private final InstrumentRepository repository;

    public InstrumentService(InstrumentRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "instruments", unless = "#result == null")
    public InstrumentView findBySymbol(String symbol) {
        return repository.findById(symbol)
                .map(InstrumentEntity::toView)
                .orElseThrow(() -> NotFoundException.instrument(symbol));
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "instrument-list")
    public List<InstrumentView> findAll() {
        return repository.findAllByOrderBySymbolAsc().stream()
                .map(InstrumentEntity::toView)
                .toList();
    }

    /**
     * The tradeable universe, used by the tick simulator.
     *
     * <p>Deliberately NOT cached: the simulator calls it on a schedule and a stale answer would
     * mean generating ticks for a halted instrument. Cheap query, real consequence.
     */
    @Transactional(readOnly = true)
    public List<InstrumentEntity> activeInstruments() {
        return repository.findByStatusOrderBySymbolAsc(InstrumentStatus.ACTIVE);
    }
}
