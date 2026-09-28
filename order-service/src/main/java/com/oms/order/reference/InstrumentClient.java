package com.oms.order.reference;

import com.oms.common.reference.InstrumentView;

/**
 * Reference data lookup, as seen by the order path.
 *
 * <p>An interface with two implementations selected by configuration, rather than one class
 * with a flag inside it. The order path depends on the abstraction; how the data is
 * obtained is a deployment concern.
 */
public interface InstrumentClient {

    /**
     * @throws com.oms.common.error.NotFoundException if the symbol is unknown
     * @throws com.oms.common.error.UpstreamUnavailableException if reference data cannot be
     *                                                           reached and nothing is cached
     */
    InstrumentView findBySymbol(String symbol);
}
