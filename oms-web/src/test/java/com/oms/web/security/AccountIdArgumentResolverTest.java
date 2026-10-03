package com.oms.web.security;

import com.oms.common.error.ErrorCode;
import com.oms.common.error.OmsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@code @AccountId} resolution.
 *
 * <p>The failure cases matter more than the happy path here: the whole reason this resolver
 * throws instead of returning null is that a controller handed a null account would go on to
 * query "every order for account null", and whether that returns nothing or everything is not a
 * question worth having.
 */
class AccountIdArgumentResolverTest {

    private final AccountIdArgumentResolver resolver = new AccountIdArgumentResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("resolves the account_id claim from the token in the security context")
    void resolvesFromTheToken() throws Exception {
        authenticate(jwtWithAccount("ACC-TRADER-1"));

        assertThat(resolver.resolveArgument(parameter("required"), null, null, null))
                .isEqualTo("ACC-TRADER-1");
    }

    @Test
    @DisplayName("supports a String parameter annotated @AccountId, and nothing else")
    void supportsOnlyAnnotatedStrings() throws Exception {
        assertThat(resolver.supportsParameter(parameter("required"))).isTrue();
        assertThat(resolver.supportsParameter(parameter("optional"))).isTrue();

        assertThat(resolver.supportsParameter(parameter("unannotated")))
                .as("a bare String must still come from the request, not from the token")
                .isFalse();
        assertThat(resolver.supportsParameter(parameter("wrongType")))
                .as("annotating a non-String is a programming error, not a conversion request")
                .isFalse();
    }

    @Test
    @DisplayName("an unauthenticated caller is refused at the boundary, not passed a null account")
    void unauthenticatedIsRefused() throws Exception {
        MethodParameter required = parameter("required");

        OmsException thrown = catchThrowableOfType(
                () -> resolver.resolveArgument(required, null, null, null), OmsException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("a non-JWT authentication is also refused - this resolver only trusts a claim")
    void nonJwtAuthenticationIsRefused() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("someone", "creds", "ROLE_TRADER"));
        MethodParameter required = parameter("required");

        assertThatThrownBy(() -> resolver.resolveArgument(required, null, null, null))
                .isInstanceOf(OmsException.class);
    }

    @Test
    @DisplayName("a validly signed token with no account claim is refused, and says which claim")
    void missingClaimNamesTheClaim() throws Exception {
        authenticate(Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("ops1")
                .claim(OmsClaims.ROLES, List.of("ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build());
        MethodParameter required = parameter("required");

        assertThatThrownBy(() -> resolver.resolveArgument(required, null, null, null))
                .isInstanceOf(OmsException.class)
                .hasMessageContaining(OmsClaims.ACCOUNT_ID);
    }

    @Test
    @DisplayName("a blank account claim is treated as missing, not propagated as an empty account")
    void blankClaimIsMissing() throws Exception {
        authenticate(jwtWithAccount("   "));
        MethodParameter required = parameter("required");

        assertThatThrownBy(() -> resolver.resolveArgument(required, null, null, null))
                .isInstanceOf(OmsException.class);
    }

    @Test
    @DisplayName("required=false yields null for an anonymous caller instead of refusing")
    void optionalYieldsNullWhenAnonymous() throws Exception {
        assertThat(resolver.resolveArgument(parameter("optional"), null, null, null)).isNull();
    }

    @Test
    @DisplayName("required=false yields null when the token carries no account claim")
    void optionalYieldsNullWhenClaimAbsent() throws Exception {
        authenticate(jwtWithAccount(""));

        assertThat(resolver.resolveArgument(parameter("optional"), null, null, null)).isNull();
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject()));
    }

    private static Jwt jwtWithAccount(String accountId) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("trader1")
                .claim(OmsClaims.ACCOUNT_ID, accountId)
                .claim(OmsClaims.ROLES, List.of("TRADER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();
    }

    private static MethodParameter parameter(String methodName) throws NoSuchMethodException {
        Method method = Signatures.class.getDeclaredMethod(methodName, methodName.equals("wrongType")
                ? Long.class
                : String.class);
        return new MethodParameter(method, 0);
    }

    /** Parameter shapes the resolver has to distinguish between. */
    @SuppressWarnings("unused")
    static class Signatures {
        void required(@AccountId String accountId) {
        }

        void optional(@AccountId(required = false) String accountId) {
        }

        void unannotated(String accountId) {
        }

        void wrongType(@AccountId Long accountId) {
        }
    }
}
