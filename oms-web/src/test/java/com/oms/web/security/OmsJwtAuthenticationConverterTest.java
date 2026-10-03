package com.oms.web.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The roles-claim mapping, which is the one piece of security wiring that fails *silently*:
 * get it wrong and every rule denies, with no error anywhere.
 */
class OmsJwtAuthenticationConverterTest {

    private final OmsJwtAuthenticationConverter converter = new OmsJwtAuthenticationConverter();

    @Test
    @DisplayName("bare role names in the token become ROLE_-prefixed authorities")
    void prefixesRoles() {
        AbstractAuthenticationToken token = converter.convert(jwt("ACC-1", "trader1", List.of("TRADER")));

        assertThat(authorityNames(token)).containsExactly("ROLE_TRADER");
    }

    @Test
    @DisplayName("every role is mapped, so a multi-role token gets all of them")
    void mapsEveryRole() {
        AbstractAuthenticationToken token =
                converter.convert(jwt("ACC-1", "ops1", List.of("RISK", "ADMIN")));

        assertThat(authorityNames(token)).containsExactlyInAnyOrder("ROLE_RISK", "ROLE_ADMIN");
    }

    @Test
    @DisplayName("a token that already carries the prefix does not become ROLE_ROLE_TRADER")
    void doesNotDoublePrefix() {
        AbstractAuthenticationToken token =
                converter.convert(jwt("ACC-1", "trader1", List.of("ROLE_TRADER")));

        assertThat(authorityNames(token)).containsExactly("ROLE_TRADER");
    }

    @Test
    @DisplayName("role names are upper-cased and trimmed, because hasRole is case-sensitive")
    void normalisesCaseAndWhitespace() {
        AbstractAuthenticationToken token =
                converter.convert(jwt("ACC-1", "trader1", List.of(" trader ")));

        assertThat(authorityNames(token)).containsExactly("ROLE_TRADER");
    }

    @Test
    @DisplayName("blank entries are dropped rather than producing a bare ROLE_ authority")
    void dropsBlankRoles() {
        AbstractAuthenticationToken token =
                converter.convert(jwt("ACC-1", "trader1", List.of("TRADER", "", "   ")));

        assertThat(authorityNames(token)).containsExactly("ROLE_TRADER");
    }

    @Test
    @DisplayName("no roles claim yields no authorities - authenticated but permitted nothing")
    void missingRolesClaimIsNotAnError() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("someone")
                .claim(OmsClaims.ACCOUNT_ID, "ACC-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        assertThat(OmsJwtAuthenticationConverter.authorities(jwt)).isEmpty();
    }

    @Test
    @DisplayName("an unknown role is mapped, not rejected: a new issuer role must not break tokens")
    void unknownRolesAreHarmless() {
        AbstractAuthenticationToken token =
                converter.convert(jwt("ACC-1", "x", List.of("TRADER", "SETTLEMENT_CLERK")));

        assertThat(authorityNames(token))
                .containsExactlyInAnyOrder("ROLE_TRADER", "ROLE_SETTLEMENT_CLERK");
    }

    @Test
    @DisplayName("duplicate roles collapse - the authority set is a set")
    void duplicatesCollapse() {
        AbstractAuthenticationToken token =
                converter.convert(jwt("ACC-1", "x", List.of("TRADER", "trader", "ROLE_TRADER")));

        assertThat(authorityNames(token)).containsExactly("ROLE_TRADER");
    }

    @Test
    @DisplayName("the principal name is the subject - who is calling, not whose money it is")
    void principalIsTheSubject() {
        AbstractAuthenticationToken token = converter.convert(jwt("ACC-99", "trader1", List.of("TRADER")));

        assertThat(token.getName()).isEqualTo("trader1");
        assertThat(token).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(((JwtAuthenticationToken) token).getToken().getClaimAsString(OmsClaims.ACCOUNT_ID))
                .isEqualTo("ACC-99");
    }

    @Test
    @DisplayName("with no subject the principal falls back to the account, never to null")
    void principalFallsBackToAccount() {
        Jwt noSubject = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claims(c -> c.putAll(Map.of(
                        OmsClaims.ACCOUNT_ID, "ACC-7",
                        OmsClaims.ROLES, List.of("TRADER"))))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        assertThat(converter.convert(noSubject).getName()).isEqualTo("ACC-7");
    }

    @Test
    @DisplayName("the authority collection cannot be mutated by a caller")
    void authoritiesAreUnmodifiable() {
        var authorities = OmsJwtAuthenticationConverter.authorities(
                jwt("ACC-1", "x", List.of("TRADER")));

        assertThat(authorities).isUnmodifiable();
    }

    private static List<String> authorityNames(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }

    private static Jwt jwt(String accountId, String subject, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuer("http://" + OmsClaims.ISSUER)
                .audience(List.of(OmsClaims.AUDIENCE))
                .claim(OmsClaims.ACCOUNT_ID, accountId)
                .claim(OmsClaims.ROLES, roles)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                .build();
    }
}
