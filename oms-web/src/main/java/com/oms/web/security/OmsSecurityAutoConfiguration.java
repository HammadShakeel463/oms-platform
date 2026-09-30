package com.oms.web.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Wires the shared security helpers into any servlet service that is a resource server.
 *
 * <p>{@code @ConditionalOnClass(Jwt.class)} is the whole point: {@code oms-web} declares
 * {@code spring-boot-starter-oauth2-resource-server} as {@code provided}, so it compiles against
 * the API without forcing it onto every consumer. A service that does not include the starter gets
 * the error handler and the trace filter and none of this; a service that does gets all of it by
 * adding one dependency.
 *
 * <p>Same reasoning as {@code PersistenceExceptionHandler} being conditional on
 * {@code DataAccessException}: a shared library that assumes every consumer has every dependency
 * is a library with one consumer.
 */
@AutoConfiguration
@ConditionalOnClass(Jwt.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OmsSecurityAutoConfiguration {

    /**
     * The roles-claim converter, shared so all four services derive authorities identically.
     *
     * <p>A service that mapped roles differently would be a service with a different security
     * model - and that is the kind of divergence nobody notices until an endpoint that should be
     * restricted is not.
     */
    @Bean
    @ConditionalOnMissingBean
    public OmsJwtAuthenticationConverter omsJwtAuthenticationConverter() {
        return new OmsJwtAuthenticationConverter();
    }

    /**
     * Registers the {@link AccountId} resolver with Spring MVC.
     *
     * <p>A {@code WebMvcConfigurer} rather than a bare bean: argument resolvers are not picked up
     * by type, they have to be added to the list MVC builds at startup. The custom ones run after
     * the built-in resolvers, which is why {@code @AccountId} cannot accidentally shadow
     * {@code @RequestParam}.
     */
    @Bean
    @ConditionalOnMissingBean(name = "omsAccountIdWebMvcConfigurer")
    public WebMvcConfigurer omsAccountIdWebMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new AccountIdArgumentResolver());
            }
        };
    }
}
