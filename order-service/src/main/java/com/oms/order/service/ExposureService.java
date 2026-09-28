package com.oms.order.service;

import com.oms.common.domain.Side;
import com.oms.order.domain.PositionExposureEntity;
import com.oms.order.repository.PositionExposureRepository;
import org.springframework.stereotype.Service;

/**
 * Maintains order-service's own exposure projection: filled position plus working
 * quantity, per (account, symbol).
 *
 * <p>Updated from three places, all inside the transaction that caused them:
 * an order going live adds working quantity, a fill moves working into net, and a cancel
 * confirmation removes working quantity.
 */
@Service
public class ExposureService {

    private final PositionExposureRepository repository;

    public ExposureService(PositionExposureRepository repository) {
        this.repository = repository;
    }

    /**
     * Loads the exposure row, creating a zeroed one if this is the first order in the
     * symbol. Not persisted until something actually changes it.
     */
    public PositionExposureEntity load(String accountId, String symbol) {
        return repository.findById(new PositionExposureEntity.Key(accountId, symbol))
                .orElseGet(() -> new PositionExposureEntity(accountId, symbol));
    }

    public void orderWentLive(PositionExposureEntity exposure, Side side, long quantity) {
        exposure.addWorking(side, quantity);
        repository.save(exposure);
    }

    public void fillApplied(String accountId, String symbol, Side side, long quantity) {
        PositionExposureEntity exposure = load(accountId, symbol);
        exposure.applyFill(side, quantity);
        repository.save(exposure);
    }

    public void workingReleased(String accountId, String symbol, Side side, long quantity) {
        PositionExposureEntity exposure = load(accountId, symbol);
        exposure.removeWorking(side, quantity);
        repository.save(exposure);
    }
}
