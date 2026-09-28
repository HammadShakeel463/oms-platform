package com.oms.matching.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "oms.engine")
public record EngineProperties(

        /** Price levels per side in a published snapshot. */
        @Min(1) int snapshotDepth,

        /**
         * Recently-processed order ids remembered per book, for at-least-once deduplication.
         * Bounded: an unbounded set is a memory leak with a respectable name.
         */
        @Min(16) int dedupeCapacity,

        /**
         * Order nodes retained per book for reuse. 0 disables pooling, which is how the
         * benchmark isolates what pooling is worth.
         */
        @Min(0) int nodePoolLimit,

        /**
         * Consumer threads, and therefore books that can match in parallel.
         *
         * <p>Capped by the partition count: a partition is assigned to at most one consumer
         * in a group, so asking for more threads than partitions leaves threads idle. There
         * is no benefit in exceeding the core count either - these threads are CPU-bound
         * inside the book and there is no I/O for them to overlap.
         */
        @Min(1) int concurrency
) {

    public EngineProperties {
        if (snapshotDepth <= 0) {
            snapshotDepth = 10;
        }
        if (dedupeCapacity < 16) {
            dedupeCapacity = 65_536;
        }
        if (nodePoolLimit < 0) {
            nodePoolLimit = 4_096;
        }
        if (concurrency < 1) {
            concurrency = 3;
        }
    }
}
