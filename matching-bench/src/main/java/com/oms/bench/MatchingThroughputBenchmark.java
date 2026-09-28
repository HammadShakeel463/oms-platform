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
 * Throughput: book operations per second, on the mixed workload.
 *
 * <h2>Why each annotation is there</h2>
 *
 * <p><b>{@code @Fork(2)}</b> - each fork is a fresh JVM. The JIT's profile depends on what it
 * has already seen, so running two implementations in one JVM lets the first one shape the
 * compilation of the second: a well-known way to produce a completely wrong comparison. Two
 * forks also expose run-to-run variance, which is information, not noise to be hidden.
 *
 * <p><b>{@code @Warmup(5)}</b> - the first few thousand invocations run interpreted, then
 * C1-compiled, then C2-compiled. Measuring before the code reaches its final compiled form
 * measures the compiler. Five one-second iterations is enough for a method this size to settle.
 *
 * <p><b>{@code -XX:+AlwaysPreTouch}</b> and a fixed heap - the heap is committed up front so
 * the measurement does not include the OS handing over pages, and a fixed size removes heap
 * resizing as a variable.
 *
 * <p><b>{@code -XX:+UseSerialGC}</b> - deliberate, and the most arguable choice here. Allocation
 * rate is one of the things being compared, and a concurrent collector does its work on other
 * threads, which hides that difference in wall-clock throughput. Serial GC puts the cost of
 * allocation back in front of the mutator where the benchmark can see it. Production runs G1;
 * this setting is about making the comparison legible, and it is noted in docs/performance.md
 * as a difference from production rather than left as a trap.
 *
 * <p>The {@link Blackhole} in the loop is not decoration: without consuming the result, the JIT
 * is entitled to observe that nothing escapes and delete the call.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 2, jvmArgs = {
        "-Xms1g", "-Xmx1g",
        "-XX:+AlwaysPreTouch",
        "-XX:+UseSerialGC"
})
public class MatchingThroughputBenchmark {

    /**
     * One operation from the mixed stream: a passive limit, an aggressive limit, a market order
     * or a cancel, in the proportions {@link Workload} documents.
     */
    @Benchmark
    public void mixedWorkload(BookState state, Blackhole blackhole) {
        state.nextOperation(blackhole);
    }
}
