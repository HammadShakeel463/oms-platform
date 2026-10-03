package com.oms.web;

import com.oms.common.error.ErrorCode;
import com.oms.common.error.OmsException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shared error contract, exercised through a real MVC dispatch.
 *
 * <p><b>Why MockMvc standalone rather than calling the handler methods directly.</b> Calling
 * {@code handler.handleValidation(e, request)} with a hand-built exception would pass while the
 * annotation wiring was broken - and the wiring is most of what this class is. A standalone
 * {@code MockMvc} runs Spring's real {@code ExceptionHandlerExceptionResolver} against a probe
 * controller, so the test fails if an {@code @ExceptionHandler} stops matching, if advice
 * ordering changes, or if the response body shape drifts. It needs no application context, so it
 * runs in milliseconds.
 *
 * <p>Both advices are registered together, in the order Boot would register them, because
 * {@link PersistenceExceptionHandler} vs {@link ApiExceptionHandler} precedence is a documented
 * decision and therefore something a test should hold.
 */
class ApiExceptionHandlerTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // setControllerAdvice honours @Order, so this reproduces the production precedence:
        // PersistenceExceptionHandler (HIGHEST) is consulted before the Exception catch-all.
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new PersistenceExceptionHandler(), new ApiExceptionHandler())
                .build();
        // The filter is not in the chain here, so the MDC is seeded directly - this test is about
        // the handlers reading it, and TraceIdFilterTest is about it being put there.
        MDC.put(TraceIdFilter.MDC_KEY, TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("deliberate failures")
    class DeliberateFailures {

        @Test
        @DisplayName("an OmsException renders its own status and machine-readable code")
        void omsExceptionCarriesItsStatus() throws Exception {
            mvc.perform(get("/probe/oms").param("code", "RISK_LIMIT_BREACHED"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.code").value("RISK_LIMIT_BREACHED"))
                    .andExpect(jsonPath("$.message").value("probe failure"))
                    .andExpect(jsonPath("$.path").value("/probe/oms"))
                    .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("the status comes from the exception, so a new ErrorCode needs no handler change")
        void statusIsDerivedNotSwitchedOn() throws Exception {
            mvc.perform(get("/probe/oms").param("code", "ORDER_NOT_FOUND"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

            mvc.perform(get("/probe/oms").param("code", "ILLEGAL_STATE_TRANSITION"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));

            mvc.perform(get("/probe/oms").param("code", "UPSTREAM_UNAVAILABLE"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("a @Valid body failure returns 400 with one fieldErrors entry per violation")
        void bodyValidationListsEveryField() throws Exception {
            mvc.perform(post("/probe/body")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"symbol\":\"  \",\"quantity\":0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Request validation failed"))
                    .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'symbol')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'quantity')]").exists())
                    .andExpect(jsonPath("$.traceId").value(TRACE_ID));
        }

        @Test
        @DisplayName("the rejected value is echoed, because 'quantity is invalid' is not actionable")
        void rejectedValueIsReported() throws Exception {
            mvc.perform(post("/probe/body")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"symbol\":\"HBL\",\"quantity\":-5}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"))
                    .andExpect(jsonPath("$.fieldErrors[0].rejectedValue").value("-5"));
        }

        @Test
        @DisplayName("a ConstraintViolationException on a param maps to the same 400 shape")
        void paramValidationUsesTheSameShape() throws Exception {
            mvc.perform(get("/probe/constraint"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Request validation failed"))
                    // NON_EMPTY on the record: an empty fieldErrors list is omitted rather than
                    // serialised as []. A client therefore checks for presence, not for length.
                    .andExpect(jsonPath("$.fieldErrors").doesNotExist());
        }

        @Test
        @DisplayName("a missing required header names the header")
        void missingHeaderNamesIt() throws Exception {
            mvc.perform(get("/probe/header"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Missing required header: X-Probe"));
        }

        @Test
        @DisplayName("an unbindable parameter names the parameter but not the target type")
        void typeMismatchNamesTheParameter() throws Exception {
            mvc.perform(get("/probe/typed").param("quantity", "not-a-number"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Parameter 'quantity' has an invalid value"));
        }
    }

    @Nested
    @DisplayName("information disclosure")
    class InformationDisclosure {

        @Test
        @DisplayName("malformed JSON does not echo Jackson's message, which names internal types")
        void unreadableBodyDoesNotLeakTypes() throws Exception {
            mvc.perform(post("/probe/body")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"symbol\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.message", not(containsString("com.oms"))))
                    .andExpect(jsonPath("$.message", not(containsString("ProbeBody"))));
        }

        @Test
        @DisplayName("an unknown enum constant is a 400, not a 500")
        void unknownEnumIsACallerError() throws Exception {
            mvc.perform(post("/probe/body")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"symbol\":\"HBL\",\"quantity\":1,\"side\":\"SIDEWAYS\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }

        @Test
        @DisplayName("the catch-all returns 500 with the trace id and no exception detail at all")
        void unexpectedExceptionLeaksNothing() throws Exception {
            mvc.perform(get("/probe/boom"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                    .andExpect(jsonPath("$.message", not(containsString("probe internals"))))
                    .andExpect(jsonPath("$.message", not(containsString("IllegalStateException"))));
        }
    }

    @Nested
    @DisplayName("advice precedence")
    class AdvicePrecedence {

        @Test
        @DisplayName("a constraint violation reaches the persistence advice, not the Exception catch-all")
        void integrityViolationIsA409NotA500() throws Exception {
            mvc.perform(get("/probe/integrity"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_CLIENT_ORDER_ID"))
                    .andExpect(jsonPath("$.message").value("The request conflicts with existing data"));
        }

        @Test
        @DisplayName("an optimistic lock conflict is a retryable 409, not a 500")
        void optimisticLockIsA409() throws Exception {
            mvc.perform(get("/probe/optimistic"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                    .andExpect(jsonPath("$.message", containsString("retry")));
        }
    }

    @Test
    @DisplayName("a null trace id omits the field rather than serialising null")
    void absentTraceIdIsOmitted() throws Exception {
        MDC.clear();
        mvc.perform(get("/probe/oms").param("code", "FORBIDDEN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.traceId").doesNotExist());
    }

    // ------------------------------------------------------------------------------------
    //  Probe controller: one endpoint per failure mode. Deliberately trivial - the subject
    //  under test is the advice, and anything this controller does itself is noise.
    // ------------------------------------------------------------------------------------

    private static final class ProbeException extends OmsException {
        private ProbeException(ErrorCode code) {
            super(code, "probe failure");
        }
    }

    record ProbeBody(@NotBlank String symbol, @Min(1) int quantity, Side side) {
        enum Side { BUY, SELL }
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/oms")
        String oms(@RequestParam String code) {
            throw new ProbeException(ErrorCode.valueOf(code));
        }

        @PostMapping("/body")
        String body(@Valid @RequestBody ProbeBody body) {
            return body.symbol();
        }

        @GetMapping("/constraint")
        String constraint() {
            throw new ConstraintViolationException("probe", Set.of());
        }

        @GetMapping("/header")
        String header(@RequestHeader("X-Probe") String probe) {
            return probe;
        }

        @GetMapping("/typed")
        String typed(@RequestParam long quantity) {
            return String.valueOf(quantity);
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("probe internals: table oms_order.orders, column px");
        }

        @GetMapping("/integrity")
        String integrity() {
            throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"ux_orders_client_order_id\"");
        }

        @GetMapping("/optimistic")
        String optimistic() {
            throw new OptimisticLockingFailureException("row version changed");
        }
    }
}
