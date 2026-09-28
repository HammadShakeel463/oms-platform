package com.oms.common.domain;

public enum TimeInForce {

    /** Rests on the book until filled or cancelled (within the trading session). */
    DAY,

    /** Immediate-or-cancel: whatever cannot be filled at once is cancelled. */
    IOC,

    /** Fill-or-kill: filled in full immediately, or cancelled in full. */
    FOK;

    /** True when unfilled residual quantity must never rest on the book. */
    public boolean isImmediate() {
        return this == IOC || this == FOK;
    }
}
