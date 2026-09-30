package com.oms.order.api;

import com.oms.common.domain.OrderStatus;
import com.oms.order.api.dto.OrderAuditResponse;
import com.oms.order.api.dto.OrderResponse;
import com.oms.order.api.dto.PageResponse;
import com.oms.order.api.dto.PlaceOrderRequest;
import com.oms.order.domain.OrderEntity;
import com.oms.order.service.OrderService;
import com.oms.order.service.PlaceOrderCommand;
import com.oms.web.security.AccountId;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Order entry and enquiry.
 *
 * <p><b>The controller does four things and no more:</b> bind and validate the request, map
 * it to a command, call the service, map the result to a response. No business logic, no
 * transaction, no repository. That is what makes the service testable without HTTP and the
 * controller testable without a database.
 *
 * <p><b>The account comes from a verified JWT claim</b>, resolved by {@code @AccountId}. Phases 2
 * to 4 read an {@code X-Account-Id} header, which was client-supplied and therefore trivially
 * spoofable - acceptable only because nothing was authenticated yet. Reading it in exactly one
 * place made the swap a one-line change per endpoint, which was the point of putting it there.
 *
 * <p>{@code caller} is the JWT subject: <em>who</em> placed the order, as opposed to <em>whose
 * account</em> it is for. It lands in the {@code actor} column of the audit trail, which is the
 * difference between "ACC-TRADER-1 cancelled this" and "hammad cancelled this on behalf of
 * ACC-TRADER-1".
 */
@RestController
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Places an order.
     *
     * <p>Returns 201 with a {@code Location} header. Not 200: a new resource was created and
     * has an address. The response body is the order as accepted, which for a client is the
     * difference between "the server has it" and "the server has it, here is its id and
     * current state".
     */
    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @AccountId String accountId,
            @AuthenticationPrincipal Jwt caller,
            @Valid @RequestBody PlaceOrderRequest request,
            UriComponentsBuilder uriBuilder) {

        OrderEntity order = orderService.placeOrder(new PlaceOrderCommand(
                accountId,
                request.clientOrderId(),
                request.symbol(),
                request.side(),
                request.orderType(),
                request.timeInForce(),
                request.limitPrice(),
                request.quantity(),
                callerId(caller)));

        URI location = uriBuilder.path("/api/v1/orders/{orderId}")
                .buildAndExpand(order.getOrderId())
                .toUri();

        return ResponseEntity.created(location).body(OrderResponse.from(order));
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@AccountId String accountId,
                                  @PathVariable UUID orderId) {
        return OrderResponse.from(orderService.findOrder(orderId, accountId));
    }

    @GetMapping
    public PageResponse<OrderResponse> listOrders(
            @AccountId String accountId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {

        return PageResponse.of(
                orderService.search(accountId, symbol, status, PageRequest.of(page, size)),
                OrderResponse::from);
    }

    /**
     * Requests cancellation.
     *
     * <p>202 Accepted, not 200 OK, and the distinction is the design. The matching engine
     * owns the book, so at this moment the cancel has been <em>requested</em>, not
     * performed - the order may be filling as the request arrives. The client polls or
     * listens for the order to reach CANCELLED. Returning 200 here would be a lie that
     * eventually costs somebody money.
     */
    @DeleteMapping("/{orderId}")
    public ResponseEntity<OrderResponse> cancelOrder(
            @AccountId String accountId,
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID orderId) {

        OrderEntity order = orderService.requestCancel(orderId, accountId, callerId(caller));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(OrderResponse.from(order));
    }

    /** The full immutable history of an order. */
    @GetMapping("/{orderId}/audit")
    public List<OrderAuditResponse> auditTrail(@AccountId String accountId,
                                               @PathVariable UUID orderId) {
        return orderService.auditTrail(orderId, accountId).stream()
                .map(OrderAuditResponse::from)
                .toList();
    }
    /**
     * The JWT subject, for the audit trail. Null-safe because a service-to-service token may carry
     * no subject, in which case the audit row falls back to the generic API actor.
     */
    private static String callerId(Jwt caller) {
        return caller != null ? caller.getSubject() : null;
    }
}
