package com.oms.matching.book;

import org.junit.jupiter.api.DisplayName;

/**
 * The same contract, run against the baseline.
 *
 * <p>This is what makes the performance comparison in docs/performance.md legitimate: the two
 * books are measured against each other only because they are first proven to behave
 * identically. A faster implementation that fails the contract is not faster, it is wrong.
 */
@DisplayName("NaiveOrderBook satisfies the same contract")
class NaiveOrderBookContractTest extends LimitOrderBookContractTest {

    @Override
    protected LimitOrderBook newBook(String symbol) {
        return new NaiveOrderBook(symbol);
    }
}
