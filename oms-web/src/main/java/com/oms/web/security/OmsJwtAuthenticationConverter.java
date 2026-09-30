package com.oms.web.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the {@code roles} claim into Spring Security authorities.
 *
 * <p>Without this, Spring maps the OAuth2 {@code scope}/{@code scp} claim to {@code SCOPE_*}
 * authorities and nothing else - so {@code hasRole("TRADER")} would never match, and every
 * authorisation rule would silently deny. It is the single most common reason a resource server
 * "ignores" its roles.
 *
 * <p>The {@code ROLE_} prefix is applied here and nowhere else. Spring's {@code hasRole("TRADER")}
 * prepends it and {@code hasAuthority("TRADER")} does not; having the prefix appear in the token,
 * in the converter and in the rules is how a project ends up with {@code ROLE_ROLE_TRADER}. The
 * token carries bare role names, and this class is the one place that knows about the convention.
 *
 * <p>Shared across all four services so the mapping cannot diverge - a service that read roles
 * differently would be a service with a different security model.
 */
public final class OmsJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), principalName(jwt));
    }

    /**
     * Roles from the token, prefixed. Unknown role names are mapped rather than rejected: an
     * issuer adding a role this service has not heard of should not make its tokens unusable, and
     * an authority nothing grants access to is harmless.
     */
    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(OmsClaims.ROLES);
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        return roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(role -> role.trim().toUpperCase())
                // Defensive: a token that already carries the prefix must not become ROLE_ROLE_X.
                .map(role -> role.startsWith(OmsRoles.PREFIX) ? role : OmsRoles.PREFIX + role)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The principal name used in logs and audit rows: the subject, which is the user, not the
     * account.
     */
    private static String principalName(Jwt jwt) {
        String subject = jwt.getSubject();
        return subject != null ? subject : jwt.getClaimAsString(OmsClaims.ACCOUNT_ID);
    }
}
