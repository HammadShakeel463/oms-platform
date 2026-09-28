package com.oms.common.reference;

public enum InstrumentStatus {

    /** Open for trading. */
    ACTIVE,

    /** Temporarily suspended; orders are rejected, existing book is retained. */
    HALTED,

    /** No longer listed; orders are rejected. */
    DELISTED
}
