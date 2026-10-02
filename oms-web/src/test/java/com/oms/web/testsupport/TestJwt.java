package com.oms.web.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.oms.web.security.OmsClaims;
import com.oms.web.security.ResourceServerSupport;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints real, signed tokens for integration tests, and the decoder that verifies them.
 *
 * <h2>Why not just stub the decoder</h2>
 *
 * <p>An integration test could inject a decoder that returns a hand-built {@code Jwt} for any
 * input. That would skip the one thing worth exercising end to end: that a token the platform
 * actually issues passes the platform's own validator, with the right issuer, the right
 * audience and an unexpired timestamp. Signing a real token and verifying it with
 * {@link ResourceServerSupport#tokenValidator()} - the production validator, not a test copy -
 * means an integration test fails if the claim contract or the validation rules drift.
 *
 * <p>What is replaced is only the <em>key source</em>: production fetches a JWK set from the
 * gateway over HTTP, and no gateway runs during a service's integration test. Everything else
 * is the real path.
 *
 * <h2>One key per JVM</h2>
 *
 * <p>The key pair is generated once and held statically. RSA key generation takes tens of
 * milliseconds, and a Spring context is cached across test classes, so a per-instance key
 * would mean tokens minted by one test class failing in another that reused the context -
 * an intermittent failure with a confusing message.
 */
public final class TestJwt {

    private static final RSAKey KEY = generate();

    private TestJwt() {
    }

    private static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("test-" + UUID.randomUUID())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a test signing key", e);
        }
    }

    /**
     * A decoder that trusts the test key and applies the <b>production</b> validator, so a
     * token with the wrong issuer or audience fails here exactly as it would in production.
     */
    public static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(ResourceServerSupport.tokenValidator());
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not build the test decoder", e);
        }
    }

    /** A signed token for an account, with the given bare role names. */
    public static String token(String accountId, String subject, String... roles) {
        return token(accountId, subject, List.of(roles), Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    /** A token that has already expired, for asserting that expiry is actually enforced. */
    public static String expiredToken(String accountId, String subject, String... roles) {
        return token(accountId, subject, List.of(roles), Instant.now().minus(1, ChronoUnit.HOURS));
    }

    private static String token(String accountId, String subject,
                                List<String> roles, Instant expiresAt) {
        Instant issuedAt = expiresAt.minus(15, ChronoUnit.MINUTES);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(OmsClaims.ISSUER)
                .audience(OmsClaims.AUDIENCE)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim(OmsClaims.ACCOUNT_ID, accountId)
                .claim(OmsClaims.ROLES, roles)
                .claim(OmsClaims.DISPLAY_NAME, subject)
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(KEY));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the test token", e);
        }
        return jwt.serialize();
    }

    /** The value for an {@code Authorization} header. */
    public static String bearer(String accountId, String subject, String... roles) {
        return "Bearer " + token(accountId, subject, roles);
    }
}
