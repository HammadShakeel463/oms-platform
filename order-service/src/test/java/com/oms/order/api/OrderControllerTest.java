package com.oms.order.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.domain.OrderStatus;
import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.error.ConflictException;
import com.oms.common.error.NotFoundException;
import com.oms.common.error.RiskRejectedException;
import com.oms.order.TestFixtures;
import com.oms.order.api.dto.PlaceOrderRequest;
import com.oms.order.config.SecurityConfig;
import com.oms.order.domain.OrderEntity;
import com.oms.order.service.OrderService;
import com.oms.order.service.PlaceOrderCommand;
import com.oms.web.security.OmsClaims;
import com.oms.web.security.OmsRoles;
import com.oms.web.security.OmsSecurityAutoConfiguration;
import com.oms.web.OmsWebAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test, now including the real security rules.
 *
 * <p>{@code @WebMvcTest} starts only the MVC infrastructure - controllers, converters, filters and
 * {@code @RestControllerAdvice} - with no database, no Kafka and no service beans.
 *
 * <p>Three imports make this slice meaningful rather than a formality:
 * {@link OmsWebAutoConfiguration} supplies the shared error advice and the trace filter,
 * {@link OmsSecurityAutoConfiguration} supplies the roles converter and the {@code @AccountId}
 * resolver, and {@link SecurityConfig} is the service's own filter chain - so the authorisation
 * rules under test are the ones that actually ship. Custom auto-configurations from a library jar
 * are not on the {@code @WebMvcTest} whitelist, which is why they are imported explicitly.
 *
 * <p>{@code jwt()} from {@code spring-security-test} installs a verified {@code Jwt} into the
 * security context without signing or decoding anything. That is the right seam: signature
 * verification is Spring Security's code and is tested by Spring Security. What is worth testing
 * here is what happens <em>after</em> a token is accepted - which role reaches which endpoint, and
 * whether the account actually comes from the claim.
 */
@WebMvcTest(OrderController.class)
@Import({OmsWebAutoConfiguration.class, OmsSecurityAutoConfiguration.class, SecurityConfig.class})
@TestPropertySource(properties =
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/oauth2/jwks")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    /**
     * The real decoder would try to fetch a JWK set over HTTP at startup. Mocked out because the
     * {@code jwt()} post-processor bypasses decoding entirely.
     */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    // --- token helpers ---------------------------------------------------------------

    private static RequestPostProcessor caller(String accountId, String... roles) {
        return jwt().jwt(builder -> builder
                        .subject("hammad")
                        .claim(OmsClaims.ACCOUNT_ID, accountId)
                        .claim(OmsClaims.ROLES, List.of(roles))
                        .claim(OmsClaims.DISPLAY_NAME, "Test Caller"))
                .authorities(java.util.Arrays.stream(roles)
                        .map(role -> (org.springframework.security.core.GrantedAuthority)
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority(OmsRoles.PREFIX + role))
                        .toList());
    }

    private static RequestPostProcessor trader() {
        return caller(TestFixtures.ACCOUNT, OmsRoles.TRADER);
    }

    private static RequestPostProcessor riskOfficer() {
        return caller(TestFixtures.ACCOUNT, OmsRoles.RISK);
    }

    private static OrderEntity routedOrder() {
        OrderEntity order = TestFixtures.limitOrder(Side.BUY, 1_000, "172.4500");
        order.transitionTo(OrderStatus.VALIDATED, null);
        order.transitionTo(OrderStatus.ROUTED, null);
        return order;
    }

    private static PlaceOrderRequest validRequest() {
        return new PlaceOrderRequest("cl-001", "HBL", Side.BUY, OrderType.LIMIT,
                TimeInForce.DAY, new BigDecimal("172.4500"), 1_000);
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    // =================================================================================
    //  Authentication and authorisation
    // =================================================================================

    @Test
    @DisplayName("no token is 401, in the platform error contract")
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("bearer token")));
    }

    @Test
    @DisplayName("a RISK officer cannot place an order - 403, not 401")
    void riskCannotTrade() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .with(riskOfficer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Separation of duties: a risk officer who can also trade is not a control. 403 rather
        // than 401 matters - it tells the client that authenticating differently will not help.
    }

    @Test
    @DisplayName("a RISK officer can read orders")
    void riskCanRead() throws Exception {
        when(orderService.search(anyString(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/orders").with(riskOfficer()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a token with no account claim is refused rather than passed through as null")
    void tokenWithoutAccountClaimIsRefused() throws Exception {
        RequestPostProcessor noAccount = jwt().jwt(builder -> builder
                        .subject("hammad")
                        .claim(OmsClaims.ROLES, List.of(OmsRoles.TRADER)))
                .authorities(new org.springframework.security.core.authority
                        .SimpleGrantedAuthority(OmsRoles.ROLE_TRADER));

        mockMvc.perform(post("/api/v1/orders")
                        .with(noAccount)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                // A null account reaching the service would query "all orders for account null",
                // and the interesting question would be whether that returns nothing or everything.
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString(OmsClaims.ACCOUNT_ID)));
    }

    @Test
    @DisplayName("the account used by the service comes from the CLAIM, not from a header")
    void accountComesFromTheClaimNotAHeader() throws Exception {
        when(orderService.placeOrder(any(PlaceOrderCommand.class))).thenReturn(routedOrder());

        mockMvc.perform(post("/api/v1/orders")
                        .with(caller("ACC-REAL", OmsRoles.TRADER))
                        // A spoofed header, which earlier phases would have trusted.
                        .header("X-Account-Id", "ACC-SOMEONE-ELSE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isCreated());

        var captor = org.mockito.ArgumentCaptor.forClass(PlaceOrderCommand.class);
        org.mockito.Mockito.verify(orderService).placeOrder(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().accountId())
                .as("the header must be ignored entirely")
                .isEqualTo("ACC-REAL");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().submittedBy())
                .as("the audit actor is the JWT subject - who acted, not whose account")
                .isEqualTo("hammad");
    }

    // =================================================================================
    //  Behaviour
    // =================================================================================

    @Test
    @DisplayName("POST returns 201 with a Location header and the accepted order")
    void placeOrderReturns201() throws Exception {
        OrderEntity order = routedOrder();
        when(orderService.placeOrder(any(PlaceOrderCommand.class))).thenReturn(order);

        mockMvc.perform(post("/api/v1/orders")
                        .with(trader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "http://localhost/api/v1/orders/" + order.getOrderId()))
                .andExpect(jsonPath("$.orderId").value(order.getOrderId().toString()))
                .andExpect(jsonPath("$.status").value("ROUTED"))
                .andExpect(jsonPath("$.leavesQuantity").value(1_000))
                .andExpect(jsonPath("$.limitPrice").value(172.45));
    }

    @Test
    @DisplayName("a response always carries the trace id, in the body and the header")
    void traceIdIsPropagated() throws Exception {
        when(orderService.findOrder(any(UUID.class), anyString()))
                .thenThrow(NotFoundException.order("x"));

        mockMvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()).with(trader()))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("bean validation failures come back as 400 with a per-field breakdown")
    void validationFailureReturns400() throws Exception {
        var invalid = new PlaceOrderRequest("", "hbl", Side.BUY, OrderType.LIMIT,
                TimeInForce.DAY, new BigDecimal("172.456789"), 0);

        mockMvc.perform(post("/api/v1/orders")
                        .with(trader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        org.hamcrest.Matchers.hasItems("clientOrderId", "symbol", "quantity",
                                "limitPrice")));
    }

    @Test
    @DisplayName("a risk rejection is 422 with the machine-readable code")
    void riskRejectionReturns422() throws Exception {
        doThrow(new RiskRejectedException("MAX_ORDER_VALUE",
                "Order notional 6898000.0000 exceeds the account limit 5000000.0000"))
                .when(orderService).placeOrder(any(PlaceOrderCommand.class));

        mockMvc.perform(post("/api/v1/orders")
                        .with(trader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RISK_LIMIT_BREACHED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("exceeds the account limit")))
                .andExpect(jsonPath("$.path").value("/api/v1/orders"));
    }

    @Test
    @DisplayName("a duplicate clientOrderId is 409")
    void duplicateReturns409() throws Exception {
        doThrow(ConflictException.duplicateClientOrderId(TestFixtures.ACCOUNT, "cl-001"))
                .when(orderService).placeOrder(any(PlaceOrderCommand.class));

        mockMvc.perform(post("/api/v1/orders")
                        .with(trader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CLIENT_ORDER_ID"));
    }

    @Test
    @DisplayName("an unparseable enum value is 400 and leaks no internal type names")
    void badEnumReturns400() throws Exception {
        String body = """
                {"clientOrderId":"cl-1","symbol":"HBL","side":"SIDEWAYS","orderType":"LIMIT",
                 "timeInForce":"DAY","limitPrice":"172.4500","quantity":100}
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .with(trader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("com.oms"))));
    }

    @Test
    @DisplayName("DELETE returns 202 Accepted, because a cancel is a request")
    void cancelReturns202() throws Exception {
        OrderEntity order = routedOrder();
        when(orderService.requestCancel(eq(order.getOrderId()), anyString(), any()))
                .thenReturn(order);

        mockMvc.perform(delete("/api/v1/orders/{id}", order.getOrderId()).with(trader()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ROUTED"));
    }

    @Test
    @DisplayName("an out-of-range page size is rejected by parameter validation")
    void pageSizeIsBounded() throws Exception {
        mockMvc.perform(get("/api/v1/orders").with(trader()).param("size", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("an unexpected exception becomes a 500 that reveals nothing")
    void unexpectedErrorIsOpaque() throws Exception {
        doThrow(new IllegalStateException("connection pool exhausted at com.zaxxer.hikari"))
                .when(orderService).placeOrder(any(PlaceOrderCommand.class));

        mockMvc.perform(post("/api/v1/orders")
                        .with(trader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hikari"))))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
