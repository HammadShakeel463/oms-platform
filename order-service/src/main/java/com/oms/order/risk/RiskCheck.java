package com.oms.order.risk;

import com.oms.common.error.RiskRejectedException;

/**
 * One pre-trade risk rule.
 *
 * <p>Each rule is a separate Spring bean implementing this interface. {@link RiskEngine}
 * injects {@code List<RiskCheck>} and Spring supplies every implementation on the
 * classpath, ordered by {@code @Order}. Adding a rule is therefore adding one class - no
 * registry to update, no {@code if} chain to extend, no existing file to touch.
 *
 * <p>This is dependency injection doing something a C++ codebase would do with a static
 * registrar object per rule, or with an explicit list in a composition root. The Spring
 * version trades a little compile-time visibility (you cannot see the list in the source)
 * for the property that a new rule cannot be forgotten - and it makes the rule set
 * configurable per profile, since a {@code @Profile}-annotated check simply is not in the
 * injected list.
 */
public interface RiskCheck {

    /** Stable name, used for metric tags and in the rejection payload. */
    String name();

    /**
     * @throws RiskRejectedException if the order must not proceed
     */
    void check(RiskContext context);
}
