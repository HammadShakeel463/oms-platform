package com.oms.web.security;

import com.oms.common.error.ErrorCode;
import com.oms.common.error.OmsException;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link AccountId} parameters from the JWT in the security context.
 *
 * <p>A {@code HandlerMethodArgumentResolver} is the Spring MVC extension point for "this parameter
 * comes from somewhere other than the request body or a parameter" - the same mechanism behind
 * {@code @RequestHeader} and {@code @PathVariable}. Registering one is how a project adds a first
 * class concept to its controllers without every method starting with three lines of boilerplate.
 *
 * <p>Failure behaviour is deliberate. A missing or malformed account claim raises
 * {@link ErrorCode#UNAUTHENTICATED} rather than passing null down: a controller that received a
 * null account would go on to query "all orders for account null", and the interesting question is
 * whether that returns nothing or everything. Refusing at the boundary removes the question.
 */
public final class AccountIdArgumentResolver implements HandlerMethodArgumentResolver {

    private static final class MissingAccountException extends OmsException {
        private MissingAccountException(String message) {
            super(ErrorCode.UNAUTHENTICATED, message);
        }
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AccountId.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        AccountId annotation = parameter.getParameterAnnotation(AccountId.class);
        boolean required = annotation == null || annotation.required();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            if (required) {
                throw new MissingAccountException(
                        "This endpoint requires an authenticated caller with a trading account");
            }
            return null;
        }

        Jwt jwt = jwtAuthentication.getToken();
        String accountId = jwt.getClaimAsString(OmsClaims.ACCOUNT_ID);

        if (accountId == null || accountId.isBlank()) {
            if (required) {
                // A validly signed token with no account claim is a configuration error at the
                // issuer, not a client error - but the caller still cannot be served, and saying
                // which claim is missing helps whoever has to fix it.
                throw new MissingAccountException(
                        "Token is valid but carries no " + OmsClaims.ACCOUNT_ID + " claim");
            }
            return null;
        }
        return accountId;
    }
}
