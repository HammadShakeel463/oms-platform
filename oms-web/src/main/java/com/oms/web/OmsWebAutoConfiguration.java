package com.oms.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessException;

/**
 * Boot auto-configuration for the shared web plumbing.
 *
 * <p><b>Why an auto-configuration rather than component scanning.</b> Each service scans its
 * own package ({@code com.oms.order}, {@code com.oms.matching}, ...), so beans in
 * {@code com.oms.web} are invisible to it. The options were: widen every service's scan to
 * {@code com.oms} - which also drags in whatever else ever lands under that root; add
 * {@code @Import} to five application classes - which is five places to forget; or ship this
 * module the way Spring Boot ships its own features.
 *
 * <p>The mechanism: {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * lists this class, Boot reads that file from every jar on the classpath, and the
 * {@code @Conditional} annotations decide whether each bean is actually created. Adding the
 * {@code oms-web} dependency is the whole integration - no code change in the service.
 *
 * <p>This is also the answer to "how does Spring Boot know I have a DataSource": exactly this
 * file, in Boot's own jars, with exactly these conditions. Understanding the mechanism turns
 * auto-configuration from magic into a lookup table, which is worth knowing before an
 * interviewer asks how Boot works.
 *
 * <p>{@code @ConditionalOnMissingBean} on each bean means a service can override any of this
 * by declaring its own - the standard Boot contract: sensible default, never a constraint.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OmsWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApiExceptionHandler omsApiExceptionHandler() {
        return new ApiExceptionHandler();
    }

    /**
     * Registered only when Spring's DAO exception hierarchy is on the classpath. matching-engine
     * has no database, so it gets everything above and not this.
     */
    @Bean
    @ConditionalOnClass(DataAccessException.class)
    @ConditionalOnMissingBean
    public PersistenceExceptionHandler omsPersistenceExceptionHandler() {
        return new PersistenceExceptionHandler();
    }

    /**
     * Ordered first so the trace id is in the MDC before anything else can log.
     *
     * <p>A {@code FilterRegistrationBean} rather than a bare {@code Filter} bean because it is
     * the only way to set the order explicitly. A plain filter bean is registered at a default
     * order, and "before everything else" is the entire requirement here.
     */
    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<TraceIdFilter> omsTraceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
