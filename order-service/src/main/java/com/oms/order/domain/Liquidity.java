package com.oms.order.domain;

/**
 * Whether this side of a trade provided liquidity (was resting on the book) or took it
 * (was the aggressor). Real venues price these differently under a maker-taker fee model;
 * here it is recorded because it is the kind of field whose absence a trading interviewer
 * notices.
 */
public enum Liquidity {
    MAKER,
    TAKER
}
