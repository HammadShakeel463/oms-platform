package com.oms.gateway.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.oms.web.security.OmsClaims;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints signed access tokens.
 *
 * <p>Deliberately small. Everything about who may log in lives in {@link UserStore}; everything
 * about what a token permits lives in the security rules. This class only knows how to turn an
 * authenticated identity into a signed JWT, which makes it the one place to look when a claim is
 * wrong.
 */
@Component
public class TokenIssuer {

    private final SigningKeys signingKeys;
    private final AuthProperties properties;
    private final Clock clock;

    public TokenIssuer(SigningKeys signingKeys, AuthProperties properties, Clock clock) {
        this.signingKeys = signingKeys;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Issues an access token for a user.
     *
     * <p>The claim set is the platform contract, and each one is here for a reason:
     *
     * <ul>
     *   <li>{@code sub} - who is calling. Used for audit rows and the {@code actor} column on an
     *       order transition.</li>
     *   <li>{@code account_id} - whose money is at stake. Kept separate from {@code sub} so one
     *       user can later act for several accounts, and so an operations user can act for none.</li>
     *   <li>{@code roles} - bare names; the {@code ROLE_} prefix is applied by the converter.</li>
     *   <li>{@code iss} and {@code aud} - checked by every resource server. Without an audience
     *       check, a token minted by this issuer for some other system would be accepted here.</li>
     *   <li>{@code exp} - short, because <b>there is no revocation</b>. A stateless token is valid
     *       until it expires, so the expiry IS the revocation window. Fifteen minutes is the trade
     *       between that window and how often a client has to re-authenticate.</li>
     *   <li>{@code jti} - a unique id, so a specific token can be denied by a future deny-list and
     *       so audit logs can refer to one issuance.</li>
     * </ul>
     */
    public IssuedToken issue(UserStore.User user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.tokenTtl());
        String tokenId = UUID.randomUUID().toString();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.username())
                .issuer(OmsClaims.ISSUER)
                .audience(OmsClaims.AUDIENCE)
                .jwtID(tokenId)
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim(OmsClaims.ACCOUNT_ID, user.accountId())
                .claim(OmsClaims.ROLES, user.roles())
                .claim(OmsClaims.DISPLAY_NAME, user.displayName())
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        // The key id lets a verifier pick the right key out of the JWK set, which
                        // is what makes rotation possible without a coordinated restart.
                        .keyID(signingKeys.keyId())
                        .type(com.nimbusds.jose.JOSEObjectType.JWT)
                        .build(),
                claims);

        try {
            jwt.sign(new RSASSASigner(signingKeys.rsaKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the access token", e);
        }

        return new IssuedToken(
                jwt.serialize(),
                "Bearer",
                properties.tokenTtl().toSeconds(),
                user.accountId(),
                List.copyOf(user.roles()),
                expiresAt);
    }

    /** What the token endpoint returns. Shaped like an OAuth2 token response. */
    public record IssuedToken(
            String accessToken,
            String tokenType,
            long expiresIn,
            String accountId,
            List<String> roles,
            Instant expiresAt
    ) {
    }
}
