package com.oms.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trace-id filter, including the part that only matters because servlet containers pool
 * threads.
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("generates an id when the caller sends none, and echoes it in the response header")
    void generatesAndEchoes() throws Exception {
        AtomicReference<String> seenInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, capturing(seenInsideChain));

        assertThat(seenInsideChain.get())
                .as("a log statement during the request must see an id")
                .isNotNull()
                .hasSize(32)
                .doesNotContain("-");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .as("the client needs the id to quote it in a support ticket")
                .isEqualTo(seenInsideChain.get());
    }

    @Test
    @DisplayName("honours an inbound X-Trace-Id, so a caller's id spans its own call and ours")
    void honoursInboundHeader() throws Exception {
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "caller-supplied-id");
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, capturing(seen));

        assertThat(seen.get()).isEqualTo("caller-supplied-id");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo("caller-supplied-id");
    }

    @Test
    @DisplayName("a blank inbound header is treated as absent, not propagated as an empty id")
    void blankHeaderIsReplaced() throws Exception {
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "   ");
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, capturing(seen));

        assertThat(seen.get()).isNotBlank().hasSize(32);
    }

    @Test
    @DisplayName("the MDC is cleared afterwards: a pooled thread must not inherit the last trace")
    void mdcIsClearedOnTheWayOut() throws Exception {
        filter.doFilter(request, response, capturing(new AtomicReference<>()));

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("the MDC is cleared even when the chain throws - this is why the finally exists")
    void mdcIsClearedWhenTheChainThrows() {
        FilterChain exploding = (req, res) -> {
            throw new IllegalStateException("downstream failure");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, exploding))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(TraceIdFilter.MDC_KEY))
                .as("a leaked id attributes the next unrelated request's logs to this trace")
                .isNull();
    }

    @Test
    @DisplayName("two requests get different ids")
    void idsAreNotReused() throws Exception {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        filter.doFilter(request, response, capturing(first));
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"),
                new MockHttpServletResponse(), capturing(second));

        assertThat(first.get()).isNotEqualTo(second.get());
    }

    /** A chain that records what the MDC held while the request was being handled. */
    private static FilterChain capturing(AtomicReference<String> sink) {
        return (req, res) -> sink.set(MDC.get(TraceIdFilter.MDC_KEY));
    }
}
