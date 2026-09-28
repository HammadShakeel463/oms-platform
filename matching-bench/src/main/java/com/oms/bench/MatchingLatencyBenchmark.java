package com.oms.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Latency distribution: p50, p95, p99, p99.9 for a single book operation.
 *
 * <p><b>{@code Mode.SampleTime} is the whole point of this class.</b> Throughput gives one
 * number - the mean, inverted - and a mean latency is close to useless for a trading system.
 * What matters is the shape of the tail, and a p99 cannot be recovered from an average
 * afterwards. SampleTime times individual invocations and reports the distribution, which is
 * the only mode that can answer "how bad does it get".
 *
 * <p><b>Why not {@code Mode.AverageTime}.</b> It reports the same information as Throughput in
 * different units. It would tell us the book is fast on average, which nobody doubts.
 *
 * <p><b>A caveat that belongs in the write-up, not hidden.</b> At tens of nanoseconds per
 * operation, the measurement is close to the resolution of {@code System.nanoTime()} itself,
 * and SampleTime's own timestamping is inside the measured region. The p50 here is therefore an
 * upper bound that includes a few nanoseconds of harness overhead, and the very low percentiles
 * are quantised by the clock. The comparison between implementations is still sound - both pay
 * the same overhead - but the absolute p50 should be read as "this order of magnitude", not as
 * a precise figure. docs/performance.md says so explicitly.
 *
 * <p>The tail is the interesting part regardless, and the tail is far above clock resolution:
 * a market order sweeping twenty price levels does real work, and that is what shows up at p99.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 2, jvmArgs = {
        "-Xms1g", "-Xmx1g",
        "-XX:+AlwaysPreTouch",
        "-XX:+UseSerialGC"
})
public class MatchingLatencyBenchmark {

    @Benchmark
    public void mixedWorkload(BookState state, Blackhole blackhole) {
        state.nextOperation(blackhole);
    }
}
