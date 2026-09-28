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
import com.oms.order.domain.OrderEntity;
import com.oms.order.service.OrderService;
import com.oms.order.service.PlaceOrderCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test.
 *
 * <p>{@code @WebMvcTest} starts only the MVC infrastructure - controllers, converters,
 * filters and {@code @RestControllerAdvice} - with no database, no Kafka and no service
 * beans. It is a few hundred milliseconds rather than the several seconds a full
 * {@code @SpringBootTest} costs, and it fails for web reasons only, so a failure here is
 * always about HTTP.
 *
 * <p>{@code @MockitoBean} (Spring 6.2, replacing the deprecated {@code @MockBean}) puts a
 * Mockito mock into the slice's context in place of the real bean.
 *
 * <p>What is actually being tested: status codes, the {@code Location} header, JSON field
 * names, and - most valuable of all - that every failure comes back in the one error
 * contract. That last property is invisible in unit tests of the service and is exactly
 * what a client integrates against.
 */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

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

    @Test
    @DisplayName("POST returns 201 with a Location header and the accepted order")
    void placeOrderReturns201() throws Exception {
        OrderEntity order = routedOrder();
        when(orderService.placeOrder(any(PlaceOrderCommand.class))).thenReturn(order);

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-Account-Id", TestFixtures.ACCOUNT)
                        .header("X-User-Id", "user-1")
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

        mockMvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID())
                        .header("X-Account-Id", TestFixtures.ACCOUNT))
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
                        .header("X-Account-Id", TestFixtures.ACCOUNT)
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
                        .header("X-Account-Id", TestFixtures.ACCOUNT)
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
                        .header("X-Account-Id", TestFixtures.ACCOUNT)
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
                        .header("X-Account-Id", TestFixtures.ACCOUNT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("com.oms"))));
    }

    @Test
    @DisplayName("a missing account header is 400, not 500")
    void missingAccountHeaderReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("X-Account-Id")));
    }

    @Test
    @DisplayName("DELETE returns 202 Accepted, because a cancel is a request")
    void cancelReturns202() throws Exception {
        OrderEntity order = routedOrder();
        when(orderService.requestCancel(eq(order.getOrderId()), anyString(), any()))
                .thenReturn(order);

        mockMvc.perform(delete("/api/v1/orders/{id}", order.getOrderId())
                        .header("X-Account-Id", TestFixtures.ACCOUNT))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ROUTED"));
    }

    @Test
    @DisplayName("an out-of-range page size is rejected by parameter validation")
    void pageSizeIsBounded() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .header("X-Account-Id", TestFixtures.ACCOUNT)
                        .param("size", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("an unexpected exception becomes a 500 that reveals nothing")
    void unexpectedErrorIsOpaque() throws Exception {
        doThrow(new IllegalStateException("connection pool exhausted at com.zaxxer.hikari"))
                .when(orderService).placeOrder(any(PlaceOrderCommand.class));

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-Account-Id", TestFixtures.ACCOUNT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hikari"))))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
