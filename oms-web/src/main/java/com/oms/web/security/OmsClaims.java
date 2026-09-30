package com.oms.web.security;

/**
 * The claim names this platform puts in a JWT, in one place.
 *
 * <p>Claim names are a wire contract between the token issuer and four services. Left as string
 * literals they drift: one service reads {@code account_id}, another {@code accountId}, and the
 * mismatch surfaces as an authorisation failure that looks like a permissions bug.
 *
 * <p>Naming follows the JWT convention of {@code snake_case} for private claims, alongside the
 * registered ones ({@code sub}, {@code iss}, {@code aud}, {@code exp}, {@code iat}, {@code jti})
 * which are defined by RFC 7519 and not restated here.
 */
public final class OmsClaims {

    /**
     * The trading account this token acts for.
     *
     * <p>Separate from {@code sub} on purpose. {@code sub} is <em>who</em> is calling - a human or a
     * service identity - and {@code account_id} is <em>whose money</em> is at stake. They are
     * usually one-to-one here, but conflating them makes it impossible later to let one user act
     * for several accounts, or to have an operations user act for none.
     */
    public static final String ACCOUNT_ID = "account_id";

    /** Role names without the {@code ROLE_} prefix: {@code ["TRADER"]}, {@code ["RISK","ADMIN"]}. */
    public static final String ROLES = "roles";

    /** Human-readable name, for logs and audit rows. Never used for an authorisation decision. */
    public static final String DISPLAY_NAME = "name";

    /** Token issuer. Verified by every resource server. */
    public static final String ISSUER = "oms-gateway";

    /** Intended audience. A token minted for something else must not be accepted here. */
    public static final String AUDIENCE = "oms-platform";

    private OmsClaims() {
    }
}
