package com.oms.common.error;

import com.oms.common.domain.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The error contract: the status codes, the exception hierarchy and the response body.
 *
 * <p>This is a wire contract, not an implementation detail. {@code ErrorCode} names appear in
 * every error response the platform produces, so a client switches on them - which makes renaming
 * one a breaking change and makes the status attached to each one part of the published API. These
 * tests exist to make that change loud.
 */
class ErrorContractTest {

    @Nested
    @DisplayName("ErrorCode")
    class Codes {

        @ParameterizedTest
        @EnumSource(ErrorCode.class)
        @DisplayName("every code carries a real HTTP error status and a usable default message")
        void everyCodeIsWellFormed(ErrorCode code) {
            assertThat(code.httpStatus())
                    .as("%s must map to a 4xx or 5xx; an error code that renders as 200 is a bug", code)
                    .isBetween(400, 599);
            assertThat(code.defaultMessage())
                    .as("the default is what a caller sees when nothing more specific is supplied")
                    .isNotBlank();
        }

        @Test
        @DisplayName("exactly one code is a 5xx fault - everything else is the caller being told something")
        void onlyGenuineFaultsAre5xx() {
            List<ErrorCode> serverErrors = java.util.Arrays.stream(ErrorCode.values())
                    .filter(c -> c.httpStatus() >= 500)
                    .toList();

            assertThat(serverErrors)
                    .as("a 5xx means this platform failed; miscategorising a client error as 5xx "
                            + "both misleads the caller and pollutes the error-rate alert")
                    .containsExactlyInAnyOrder(ErrorCode.INTERNAL_ERROR, ErrorCode.UPSTREAM_UNAVAILABLE);
        }

        @Test
        @DisplayName("the codes a client branches on keep their names")
        void namesAreStable() {
            // Pinned deliberately. If one of these is renamed this test fails, which is the
            // intended cost: the name is in every response body the platform has ever sent.
            assertThat(java.util.Arrays.stream(ErrorCode.values()).map(Enum::name).toList())
                    .contains("VALIDATION_FAILED", "UNAUTHENTICATED", "FORBIDDEN",
                            "ORDER_NOT_FOUND", "DUPLICATE_CLIENT_ORDER_ID",
                            "ILLEGAL_STATE_TRANSITION", "RISK_LIMIT_BREACHED",
                            "RATE_LIMITED", "INTERNAL_ERROR");
        }
    }

    @Nested
    @DisplayName("exception hierarchy")
    class Exceptions {

        @Test
        @DisplayName("every deliberate failure carries the code that determines its HTTP status")
        void exceptionsCarryTheirCode() {
            assertThat(NotFoundException.order("abc").errorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
            assertThat(NotFoundException.instrument("HBL").errorCode())
                    .isEqualTo(ErrorCode.INSTRUMENT_NOT_FOUND);
            assertThat(NotFoundException.account("ACC-1").errorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
            assertThat(ConflictException.duplicateClientOrderId("ACC-1", "C-1").errorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_CLIENT_ORDER_ID);
            assertThat(new RiskRejectedException("MAX_ORDER_VALUE", "too big").errorCode())
                    .isEqualTo(ErrorCode.RISK_LIMIT_BREACHED);
        }

        @Test
        @DisplayName("the factory messages name the thing that was not found")
        void messagesIdentifyTheSubject() {
            assertThat(NotFoundException.order("1f7c").getMessage()).contains("1f7c");
            assertThat(NotFoundException.instrument("HBL").getMessage()).contains("HBL");
            assertThat(ConflictException.duplicateClientOrderId("ACC-9", "C-42").getMessage())
                    .contains("ACC-9").contains("C-42");
        }

        @Test
        @DisplayName("a risk rejection names the check that fired, which is what the metric tags on")
        void riskRejectionNamesTheCheck() {
            RiskRejectedException e = new RiskRejectedException("FAT_FINGER_PRICE_BAND", "7% away");

            assertThat(e.check()).isEqualTo("FAT_FINGER_PRICE_BAND");
            assertThat(e.getMessage()).isEqualTo("7% away");
        }

        @Test
        @DisplayName("an illegal transition reports both states and what would have been allowed")
        void illegalTransitionIsSelfExplaining() {
            IllegalStateTransitionException e =
                    new IllegalStateTransitionException(OrderStatus.FILLED, OrderStatus.CANCELLED);

            assertThat(e.from()).isEqualTo(OrderStatus.FILLED);
            assertThat(e.to()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(e.getMessage())
                    .as("the allowed set in the message is what turns a 409 into a fixable report")
                    .contains("FILLED")
                    .contains("CANCELLED")
                    .contains(OrderStatus.FILLED.allowedTargets().toString());
        }

        @Test
        @DisplayName("an upstream failure keeps the dependency name and the original cause")
        void upstreamFailurePreservesTheCause() {
            var cause = new java.net.SocketTimeoutException("read timed out");
            UpstreamUnavailableException e = new UpstreamUnavailableException(
                    "market-data-service", "instrument lookup timed out", cause);

            assertThat(e.dependency()).isEqualTo("market-data-service");
            assertThat(e.getCause())
                    .as("dropping the cause here deletes the only evidence of why it failed")
                    .isSameAs(cause);
            assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        }

        @Test
        @DisplayName("every platform exception is unchecked, so a service method never declares it")
        void allAreUnchecked() {
            assertThat(RuntimeException.class)
                    .isAssignableFrom(OmsException.class);

            assertThatThrownBy(() -> {
                throw NotFoundException.order("x");
            }).isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("ApiError")
    class Body {

        @Test
        @DisplayName("of() derives the status and the code from the ErrorCode, never from the caller")
        void ofDerivesStatusAndCode() {
            ApiError error = ApiError.of(ErrorCode.RISK_LIMIT_BREACHED,
                    "Max position exceeded", "/api/v1/orders", "trace-1");

            assertThat(error.status()).isEqualTo(422);
            assertThat(error.code()).isEqualTo("RISK_LIMIT_BREACHED");
            assertThat(error.message()).isEqualTo("Max position exceeded");
            assertThat(error.path()).isEqualTo("/api/v1/orders");
            assertThat(error.traceId()).isEqualTo("trace-1");
            assertThat(error.fieldErrors()).isEmpty();
            assertThat(error.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("a null message falls back to the code's default rather than serialising null")
        void nullMessageFallsBack() {
            ApiError error = ApiError.of(ErrorCode.ORDER_NOT_FOUND, null, "/api/v1/orders/1", "t");

            assertThat(error.message()).isEqualTo(ErrorCode.ORDER_NOT_FOUND.defaultMessage());
        }

        @Test
        @DisplayName("validation() is always a 400 and carries one entry per rejected field")
        void validationCarriesFieldErrors() {
            ApiError error = ApiError.validation("Request validation failed", "/api/v1/orders", "t",
                    List.of(new ApiError.FieldError("quantity", "must be at least 1", "0"),
                            new ApiError.FieldError("symbol", "must not be blank", "")));

            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
            assertThat(error.fieldErrors()).hasSize(2);
            assertThat(error.fieldErrors().get(0).field()).isEqualTo("quantity");
            assertThat(error.fieldErrors().get(0).rejectedValue()).isEqualTo("0");
        }
    }
}
