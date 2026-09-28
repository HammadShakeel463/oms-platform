package com.oms.matching.api;

import com.oms.matching.book.BookSnapshot;
import com.oms.matching.engine.MatchingEngine;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * Read-only view of the books on this instance.
 *
 * <p>Every method here reads the published immutable snapshot. No endpoint can take a lock,
 * block the writer, or observe a half-updated book - which is why a depth endpoint on a
 * matching engine is safe to expose at all.
 *
 * <p>Two consequences worth stating plainly, because a reviewer will ask:
 *
 * <ul>
 *   <li><b>The data is up to one batch stale.</b> Correct and intended. A client that needs
 *       tick-by-tick depth subscribes to market data; this endpoint is for operators and
 *       debugging.</li>
 *   <li><b>An instance only knows its own partitions.</b> With several engine instances, a
 *       symbol lives on exactly one of them, and asking the wrong instance returns an empty
 *       book. In the finished system the gateway routes by symbol; for now that is a
 *       documented property rather than a hidden surprise.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/books")
@Validated
public class BookController {

    private final MatchingEngine engine;

    public BookController(MatchingEngine engine) {
        this.engine = engine;
    }

    /** Symbols with a live book on this instance. */
    @GetMapping
    public Collection<String> symbols() {
        return engine.symbols();
    }

    /**
     * Depth of book.
     *
     * <p>Returns an empty book rather than 404 for an unknown symbol: "no orders" and "not my
     * partition" are both legitimately an empty book from the caller's point of view, and a
     * depth query must never create engine state.
     */
    @GetMapping("/{symbol}")
    public BookDepthResponse depth(@PathVariable String symbol,
                                   @RequestParam(defaultValue = "10")
                                   @Min(1) @Max(50) int depth) {
        BookSnapshot snapshot = engine.snapshot(symbol);
        return BookDepthResponse.from(snapshot, depth);
    }

    /** Top of book only - the cheapest possible query. */
    @GetMapping("/{symbol}/top")
    public BookDepthResponse top(@PathVariable String symbol) {
        return BookDepthResponse.from(engine.snapshot(symbol), 1);
    }
}
