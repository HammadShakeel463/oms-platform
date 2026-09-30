package com.oms.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the trading account from the verified JWT.
 *
 * <pre>
 * public OrderResponse place(@AccountId String accountId, @Valid @RequestBody PlaceOrderRequest r)
 * </pre>
 *
 * <p>This replaces the {@code @RequestHeader("X-Account-Id")} that phases 2 to 4 used. The
 * difference is not cosmetic: a header is supplied by the caller and therefore trivially spoofable,
 * while this value comes from a claim in a signature-verified token. Every endpoint that acts on an
 * account now derives it from something the caller cannot forge.
 *
 * <p>Making it an annotation rather than a helper call has a second benefit: the account is visible
 * in the method signature. An endpoint that operates on an account says so in its declaration, and
 * one that does not cannot quietly reach for the security context halfway down a call stack.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AccountId {

    /**
     * When false, the parameter is null for an unauthenticated request instead of the request being
     * rejected. For endpoints that are readable anonymously but personalised when a token is present.
     */
    boolean required() default true;
}
