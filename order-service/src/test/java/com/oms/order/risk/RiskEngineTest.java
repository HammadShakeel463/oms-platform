package com.oms.order.risk;

import com.oms.common.domain.Side;
import com.oms.common.error.RiskRejectedException;
import com.oms.order.TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskEngineTest {

    private MeterRegistry registry;
    private List<String> callOrder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        callOrder = new ArrayList<>();
    }

    /**
     * A hand-written stub rather than a Mockito mock. For an interface this small, a stub
     * that records its calls is shorter, reads better in the failure message, and does not
     * need the test to describe behaviour it is about to assert on.
     */
    private RiskCheck stub(String name, boolean shouldReject) {
        return new RiskCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void check(RiskContext context) {
                callOrder.add(name);
                if (shouldReject) {
                    throw new RiskRejectedException(name, name + " says no");
                }
            }
        };
    }

    private RiskContext context() {
        return new RiskContext(TestFixtures.limitOrder(Side.BUY, 100, "172.4500"),
                TestFixtures.instrument(), TestFixtures.account(),
                TestFixtures.exposure(), 1_724_500L);
    }

    @Test
    @DisplayName("every check runs, in the injected order, when all pass")
    void runsAllChecksInOrder() {
        var engine = new RiskEngine(List.of(stub("A", false), stub("B", false), stub("C", false)),
                registry);

        engine.evaluate(context());

        assertThat(callOrder).containsExactly("A", "B", "C");
        assertThat(registry.counter("oms.risk.passed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("evaluation stops at the first rejection")
    void failsFast() {
        var engine = new RiskEngine(
                List.of(stub("A", false), stub("B", true), stub("C", false)), registry);

        assertThatThrownBy(() -> engine.evaluate(context()))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("B says no");

        // C never ran: the order is already refused, and evaluating later checks against
        // an order known to be invalid produces misleading messages.
        assertThat(callOrder).containsExactly("A", "B");
    }

    @Test
    @DisplayName("a rejection is counted against the check that fired it")
    void rejectionIsTaggedByCheck() {
        var engine = new RiskEngine(List.of(stub("MAX_POSITION", true)), registry);

        assertThatThrownBy(() -> engine.evaluate(context()))
                .isInstanceOf(RiskRejectedException.class);

        assertThat(registry.counter("oms.risk.rejections",
                "check", "MAX_POSITION", "symbol", TestFixtures.SYMBOL).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("evaluation is timed whether it passes or fails")
    void timerRecordsBothOutcomes() {
        var engine = new RiskEngine(List.of(stub("A", true)), registry);

        assertThatThrownBy(() -> engine.evaluate(context()))
                .isInstanceOf(RiskRejectedException.class);

        assertThat(registry.timer("oms.risk.evaluation").count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("the engine holds its own copy of the check list")
    void checkListIsDefensivelyCopied() {
        List<RiskCheck> mutable = new ArrayList<>(List.of(stub("A", false)));
        var engine = new RiskEngine(mutable, registry);

        mutable.add(stub("SNEAKY", true));

        engine.evaluate(context());
        assertThat(callOrder).containsExactly("A");
        assertThat(engine.checkNames()).containsExactly("A");
    }
}
