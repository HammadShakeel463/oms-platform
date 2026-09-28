package com.oms.matching.book;

import org.junit.jupiter.api.DisplayName;

/** The contract, run against the book that actually ships. */
@DisplayName("PriceTimeOrderBook satisfies the order book contract")
class PriceTimeOrderBookContractTest extends LimitOrderBookContractTest {

    @Override
    protected LimitOrderBook newBook(String symbol) {
        return new PriceTimeOrderBook(symbol);
    }
}
