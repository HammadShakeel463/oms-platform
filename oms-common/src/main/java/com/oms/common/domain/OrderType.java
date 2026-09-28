package com.oms.common.domain;

public enum OrderType {

    /** Executes only at {@code limitPrice} or better; rests on the book otherwise. */
    LIMIT,

    /** Executes against the best available contra price; never rests on the book. */
    MARKET;

    public boolean requiresLimitPrice() {
        return this == LIMIT;
    }
}
