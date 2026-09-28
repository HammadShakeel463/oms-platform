package com.oms.common.domain;

/**
 * Order side. {@code sign()} lets position math stay branch-free:
 * a signed quantity is {@code side.sign() * quantity}.
 */
public enum Side {

    BUY(1),
    SELL(-1);

    private final int sign;

    Side(int sign) {
        this.sign = sign;
    }

    public int sign() {
        return sign;
    }

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }

    /** True when {@code price} is acceptable for this side against a limit of {@code limitTicks}. */
    public boolean crosses(long priceTicks, long limitTicks) {
        return this == BUY ? priceTicks <= limitTicks : priceTicks >= limitTicks;
    }
}
