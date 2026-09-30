package com.oms.gateway.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the PUBLIC half of the signing key, as a JSON Web Key Set.
 *
 * <p>This is how the other four services learn to verify a token without ever holding a secret.
 * Each configures {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} to point here,
 * fetches the set on first use, caches it, and re-fetches when it sees a key id it does not know.
 * Exactly the mechanism a real identity provider uses - the only difference is which process is
 * serving it.
 *
 * <p>Unauthenticated on purpose: a public key is public, and requiring a token to fetch the key
 * needed to verify tokens is a bootstrapping problem with no solution.
 *
 * <p>The cache header is doing real work. Without it every resource server would re-fetch on a
 * cache miss, and a burst of token validations after a restart would hammer this endpoint. Five
 * minutes is short enough that a rotated key propagates quickly.
 */
@RestController
public class JwksController {

    private final SigningKeys signingKeys;

    public JwksController(SigningKeys signingKeys) {
        this.signingKeys = signingKeys;
    }

    @GetMapping("/oauth2/jwks")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                // toJSONObject() on a public JWK set: the private exponent is not present, because
                // SigningKeys.publicJwkSet already stripped it. Two layers, because publishing a
                // private key is the kind of mistake that ends a security review.
                .body(signingKeys.publicJwkSet().toJSONObject());
    }
}
