package com.oms.web;

import com.oms.web.security.AccountIdArgumentResolver;
import com.oms.web.security.OmsJwtAuthenticationConverter;
import com.oms.web.security.OmsSecurityAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auto-configuration contract, tested the way Spring Boot tests its own.
 *
 * <p>{@code ApplicationContextRunner} builds a context, applies the auto-configuration under test
 * and lets the assertions inspect the result - including the cases that matter most for a shared
 * library: <em>what happens when the optional dependency is absent</em>, and <em>can a consumer
 * override this</em>. A {@code @SpringBootTest} would answer neither, because it would start one
 * fixed classpath.
 *
 * <p>{@link FilteredClassLoader} is the mechanism for the first question: it hides a class from the
 * context so the {@code @ConditionalOnClass} branch can actually be exercised. That branch is not
 * cosmetic - it is the difference between {@code oms-web} being reusable and matching-engine, which
 * has no database, failing to start on a {@code NoClassDefFoundError} for a handler it never needed.
 */
class OmsWebAutoConfigurationTest {

    private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OmsWebAutoConfiguration.class, OmsSecurityAutoConfiguration.class));

    @Test
    @DisplayName("a servlet service gets the error advice and the trace filter by adding the dependency")
    void servletServiceGetsTheWebPlumbing() {
        servlet.run(context -> assertThat(context)
                .hasSingleBean(ApiExceptionHandler.class)
                .hasSingleBean(FilterRegistrationBean.class));
    }

    @Test
    @DisplayName("the trace filter is registered first, so the id is in the MDC before anything logs")
    void traceFilterIsOrderedFirst() {
        servlet.run(context -> {
            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);

            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            assertThat(registration.getFilter()).isInstanceOf(TraceIdFilter.class);
            assertThat(registration.getUrlPatterns()).containsExactly("/*");
        });
    }

    @Test
    @DisplayName("the persistence advice is registered when Spring's DAO support is present")
    void persistenceAdvicePresentWithDaoSupport() {
        servlet.run(context -> assertThat(context).hasSingleBean(PersistenceExceptionHandler.class));
    }

    @Test
    @DisplayName("without DataAccessException the persistence advice is absent and the rest still loads")
    void persistenceAdviceAbsentWithoutDaoSupport() {
        servlet.withClassLoader(new FilteredClassLoader(DataAccessException.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(PersistenceExceptionHandler.class)
                        .hasSingleBean(ApiExceptionHandler.class));
    }

    @Test
    @DisplayName("the security helpers appear only when the service is a resource server")
    void securityHelpersAreConditional() {
        servlet.run(context -> assertThat(context)
                .hasSingleBean(OmsJwtAuthenticationConverter.class));

        servlet.withClassLoader(new FilteredClassLoader(Jwt.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(OmsJwtAuthenticationConverter.class));
    }

    @Test
    @DisplayName("the @AccountId resolver is actually added to the MVC resolver list")
    void accountIdResolverIsRegisteredWithMvc() {
        servlet.run(context -> {
            WebMvcConfigurer configurer = context.getBean(
                    "omsAccountIdWebMvcConfigurer", WebMvcConfigurer.class);

            List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
            configurer.addArgumentResolvers(resolvers);

            assertThat(resolvers)
                    .as("a resolver bean that is never added to the list does nothing")
                    .hasSize(1)
                    .first().isInstanceOf(AccountIdArgumentResolver.class);
        });
    }

    @Test
    @DisplayName("nothing is created in a non-servlet application - the gateway is WebFlux")
    void nothingInANonServletApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OmsWebAutoConfiguration.class, OmsSecurityAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ApiExceptionHandler.class)
                        .doesNotHaveBean(OmsJwtAuthenticationConverter.class));
    }

    @Test
    @DisplayName("a service can override any of it - @ConditionalOnMissingBean is the Boot contract")
    void aServiceCanOverrideEveryBean() {
        servlet.withUserConfiguration(OverridingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ApiExceptionHandler.class);
                    assertThat(context.getBean(ApiExceptionHandler.class))
                            .isInstanceOf(OverridingConfiguration.CustomAdvice.class);
                });
    }

    @Configuration
    static class OverridingConfiguration {

        static class CustomAdvice extends ApiExceptionHandler {
        }

        @Bean
        ApiExceptionHandler omsApiExceptionHandler() {
            return new CustomAdvice();
        }
    }
}
