package com.oms.gateway.auth;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "oms.auth")
public record AuthProperties(

        /**
         * Access token lifetime.
         *
         * <p>Short on purpose. There is no revocation for a stateless JWT, so the expiry IS the
         * revocation window: a token stolen at minute one is usable until it expires. Fifteen
         * minutes trades that window against how often a client must re-authenticate.
         */
        @NotNull Duration tokenTtl,

        /** Users, hashed. Empty means demo users are created at startup with a loud warning. */
        List<UserStore.User> users
) {

    public AuthProperties {
        if (tokenTtl == null) {
            tokenTtl = Duration.ofMinutes(15);
        }
        if (users == null) {
            users = List.of();
        }
    }
}
