package com.oms.matching.engine;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic trade identifiers.
 *
 * <p><b>This small class is what makes crash recovery work.</b>
 *
 * <p>The matching engine holds its books only in memory, so after a restart - or after a
 * Kafka consumer-group rebalance moves a partition to a different instance - the book has to
 * be rebuilt by replaying the accepted-orders and cancel-requests topics for that partition
 * from the beginning. Replaying means the engine re-executes the same matches and re-emits
 * the same trades.
 *
 * <p>If trade ids were {@code UUID.randomUUID()}, every re-emitted trade would look new.
 * order-service deduplicates fills on the primary key {@code (trade_id, order_id)}, so a
 * fresh id defeats it: the replay would double-count every fill in the book's history. The
 * engine would be correct and the position would be wrong, which is the worst combination.
 *
 * <p>Derive the id from {@code (symbol, sequence)} instead, and the same replay produces
 * byte-identical trade ids. Downstream deduplication then works exactly as designed, and
 * re-emission becomes harmless rather than catastrophic. The engine does not need to
 * remember what it has published; the id itself carries that information.
 *
 * <p>This is the same discipline as a content-addressed identifier: make the name a function
 * of the thing, and idempotency stops being a protocol and becomes arithmetic. It is also why
 * the engine's sequence counter is per-symbol and reset on rebuild - the sequence has to be a
 * deterministic function of the input stream, not of wall-clock time or of how many times the
 * process has restarted.
 *
 * <p>Type 3 (name-based, MD5) UUIDs are used rather than a random type 4. MD5 is doing no
 * security work here - it is a naming function over a short string, and collision resistance
 * against an adversary is not part of the requirement.
 */
public final class TradeIds {

    private TradeIds() {
    }

    /**
     * @param symbol   the book
     * @param sequence the engine sequence number assigned to this trade, strictly increasing
     *                 per symbol and deterministic for a given input stream
     */
    public static UUID of(String symbol, long sequence) {
        // A separator that cannot appear in a symbol, so (AB, 1) and (A, B1) cannot collide.
        String name = symbol + '\u0000' + sequence;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
