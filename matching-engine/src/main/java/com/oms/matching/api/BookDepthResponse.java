package com.oms.matching.api;

import com.oms.common.money.Ticks;
import com.oms.matching.book.BookSnapshot;

import java.math.BigDecimal;
import java.util.List;

/**
 * Depth of book as a client sees it: decimals, not ticks.
 *
 * <p>The tick scale is an internal representation detail (ADR 0002). Exposing
 * {@code priceTicks: 1724500} would make every caller responsible for knowing the exponent,
 * and the first one that hardcodes the wrong power of ten misreads the market by a factor of
 * a hundred.
 */
public record BookDepthResponse(
        String symbol,
        long sequence,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal spread,
        List<LevelResponse> bids,
        List<LevelResponse> asks
) {

    public record LevelResponse(BigDecimal price, long quantity, int orderCount) {
        static LevelResponse from(BookSnapshot.Level level) {
            return new LevelResponse(Ticks.toDecimal(level.priceTicks()),
                    level.quantity(), level.orderCount());
        }
    }

    public static BookDepthResponse from(BookSnapshot snapshot, int depth) {
        return new BookDepthResponse(
                snapshot.symbol(),
                snapshot.sequence(),
                Ticks.toDecimal(snapshot.bestBidTicks()),
                Ticks.toDecimal(snapshot.bestAskTicks()),
                Ticks.toDecimal(snapshot.spreadTicks()),
                snapshot.bids().stream().limit(depth).map(LevelResponse::from).toList(),
                snapshot.asks().stream().limit(depth).map(LevelResponse::from).toList());
    }
}
