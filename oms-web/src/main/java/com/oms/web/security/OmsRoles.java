package com.oms.web.security;

/**
 * The platform role vocabulary.
 *
 * <p>Three roles, each defined by what it is <em>for</em> rather than by which endpoints happen to
 * exist today. A role that is defined as "can call these six URLs" stops meaning anything the
 * moment a seventh is added.
 *
 * <p>Spring Security distinguishes an <b>authority</b> ({@code ROLE_TRADER}) from a <b>role</b>
 * ({@code TRADER}): {@code hasRole("TRADER")} prepends the prefix, {@code hasAuthority} does not.
 * Mixing the two is the most common way a security rule silently never matches, so both forms are
 * declared here and the prefix is applied in exactly one place -
 * {@link OmsJwtAuthenticationConverter}.
 */
public final class OmsRoles {

    public static final String PREFIX = "ROLE_";

    /** Places and cancels orders, and reads its own positions. The everyday trading user. */
    public static final String TRADER = "TRADER";

    /** Reads positions and P&amp;L across every account. Cannot trade. */
    public static final String RISK = "RISK";

    /** Reference data and operational endpoints, including book depth. */
    public static final String ADMIN = "ADMIN";

    public static final String ROLE_TRADER = PREFIX + TRADER;
    public static final String ROLE_RISK = PREFIX + RISK;
    public static final String ROLE_ADMIN = PREFIX + ADMIN;

    private OmsRoles() {
    }
}
