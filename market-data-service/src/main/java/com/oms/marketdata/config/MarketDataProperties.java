package com.oms.marketdata.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "oms.marketdata")
public record MarketDataProperties(

        /** Turns the simulated feed off, e.g. for a test that wants to publish its own ticks. */
        boolean simulatorEnabled,

        /** Quoted spread, in instrument ticks. */
        @Min(1) int spreadTicks,

        /** Largest single-step move of the mid, in instrument ticks. */
        @Min(1) int maxStepTicks,

        /** Base quantity unit for quoted sizes. */
        @Min(1) long baseSize,

        /** How long a cached quote stays servable after the feed stops producing. */
        Duration quoteCacheTtl,

        /**
         * How long a streaming connection is held before the server closes it.
         *
         * <p>Bounded on purpose: an SSE connection that is never closed leaks a subscription and a
         * thread when a client vanishes without a FIN, which is the normal outcome of a laptop
         * lid closing. Clients reconnect; servers do not get to leak.
         */
        Duration streamTimeout,

        /** Heartbeat interval, so an idle stream does not look dead to an intermediate proxy. */
        Duration streamHeartbeat
) {

    public MarketDataProperties {
        if (spreadTicks < 1) {
            spreadTicks = 2;
        }
        if (maxStepTicks < 1) {
            maxStepTicks = 3;
        }
        if (baseSize < 1) {
            baseSize = 100;
        }
        if (quoteCacheTtl == null) {
            quoteCacheTtl = Duration.ofSeconds(30);
        }
        if (streamTimeout == null) {
            streamTimeout = Duration.ofMinutes(30);
        }
        if (streamHeartbeat == null) {
            streamHeartbeat = Duration.ofSeconds(15);
        }
    }
}
