package com.oms.gateway.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.oms.web.security.OmsClaims;
import com.oms.web.security.OmsRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token issuance: the claim set, the signature, and the two properties that are easy to get wrong.
 */
class TokenIssuerTest {

    private static final Instant NOW = Instant.parse("2026-09-30T09:15:00Z");

    private SigningKeys signingKeys;
    private TokenIssuer issuer;
    private UserStore.User trader;

    @BeforeEach
    void setUp() {
        signingKeys = SigningKeys.generate();
        issuer = new TokenIssuer(signingKeys,
                new AuthProperties(Duration.ofMinutes(15), List.of()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        trader = new UserStore.User("hammad",
                new BCryptPasswordEncoder().encode("secret"),
                "ACC-TRADER-1", "Hammad", List.of(OmsRoles.TRADER));
    }

    private SignedJWT parse(String token) throws ParseException {
        return SignedJWT.parse(token);
    }

    @Test
    @DisplayName("the token verifies against the PUBLIC key")
    void signatureVerifiesWithThePublicKey() throws Exception {
        String token = issuer.issue(trader).accessToken();

        JWSVerifier verifier = new RSASSAVerifier(signingKeys.publicKey());

        assertThat(parse(token).verify(verifier))
                .as("this is the whole point of RS256: verification needs only the public half, so "
                        + "a service that can check a token cannot mint one")
                .isTrue();
    }

    @Test
    @DisplayName("it is RS256, not HS256 and not none")
    void algorithmIsRsa() throws Exception {
        SignedJWT jwt = parse(issuer.issue(trader).accessToken());

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        // alg:none is the classic JWT forgery: a token with no signature that a lenient verifier
        // accepts. Asserting the algorithm is cheap insurance against ever emitting one.
        assertThat(jwt.getHeader().getAlgorithm().getName()).isNotEqualToIgnoringCase("none");
    }

    @Test
    @DisplayName("the header carries a key id, so rotation can work")
    void headerCarriesKeyId() throws Exception {
        SignedJWT jwt = parse(issuer.issue(trader).accessToken());

        assertThat(jwt.getHeader().getKeyID())
                .as("a verifier picks the right key out of the JWK set by kid; without it, "
                        + "rotation means a coordinated restart")
                .isEqualTo(signingKeys.keyId());
    }

    @Test
    @DisplayName("the claim set is the platform contract")
    void claimSet() throws Exception {
        SignedJWT jwt = parse(issuer.issue(trader).accessToken());
        var claims = jwt.getJWTClaimsSet();

        assertThat(claims.getSubject())
                .as("sub is WHO is calling - it lands in the audit trail actor column")
                .isEqualTo("hammad");
        assertThat(claims.getStringClaim(OmsClaims.ACCOUNT_ID))
                .as("account_id is WHOSE MONEY is at stake, deliberately separate from sub")
                .isEqualTo("ACC-TRADER-1");
        assertThat(claims.getStringListClaim(OmsClaims.ROLES))
                .containsExactly(OmsRoles.TRADER);
        assertThat(claims.getIssuer()).isEqualTo(OmsClaims.ISSUER);
        assertThat(claims.getAudience()).contains(OmsClaims.AUDIENCE);
        assertThat(claims.getJWTID()).isNotBlank();
    }

    @Test
    @DisplayName("roles are bare names - the ROLE_ prefix is not in the token")
    void rolesAreUnprefixed() throws Exception {
        var claims = parse(issuer.issue(trader).accessToken()).getJWTClaimsSet();

        assertThat(claims.getStringListClaim(OmsClaims.ROLES))
                .as("the prefix is applied by the converter in exactly one place; having it in the "
                        + "token as well is how a project ends up with ROLE_ROLE_TRADER")
                .containsExactly("TRADER")
                .doesNotContain("ROLE_TRADER");
    }

    @Test
    @DisplayName("expiry is short, because a stateless token cannot be revoked")
    void expiryIsShortAndExact() throws Exception {
        TokenIssuer.IssuedToken issued = issuer.issue(trader);
        var claims = parse(issued.accessToken()).getJWTClaimsSet();

        assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpirationTime().toInstant())
                .isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(claims.getNotBeforeTime().toInstant()).isEqualTo(NOW);
        assertThat(issued.expiresIn()).isEqualTo(900);
    }

    @Test
    @DisplayName("two tokens for the same user have different ids")
    void tokenIdsAreUnique() throws Exception {
        String first = parse(issuer.issue(trader).accessToken()).getJWTClaimsSet().getJWTID();
        String second = parse(issuer.issue(trader).accessToken()).getJWTClaimsSet().getJWTID();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("a token from one key does not verify against another")
    void tokensDoNotVerifyAcrossKeys() throws Exception {
        String token = issuer.issue(trader).accessToken();
        SigningKeys other = SigningKeys.generate();

        assertThat(parse(token).verify(new RSASSAVerifier(other.publicKey())))
                .as("which is exactly why tokens do not survive a gateway restart - "
                        + "documented on SigningKeys and in ADR 0007")
                .isFalse();
    }

    @Test
    @DisplayName("the published JWK set contains the public key and NOT the private one")
    void jwkSetPublishesOnlyThePublicHalf() {
        var jwkSet = signingKeys.publicJwkSet();

        assertThat(jwkSet.getKeys()).hasSize(1);
        assertThat(jwkSet.getKeys().get(0).isPrivate())
                .as("serialising the full key would publish the private exponent at an "
                        + "unauthenticated endpoint")
                .isFalse();
        assertThat(jwkSet.toJSONObject().toString())
                .doesNotContain("\"d\"")
                .doesNotContain("\"p\"")
                .doesNotContain("\"q\"");
    }
}
