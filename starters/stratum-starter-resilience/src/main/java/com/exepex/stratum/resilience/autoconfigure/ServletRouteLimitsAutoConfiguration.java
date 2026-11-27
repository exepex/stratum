package com.exepex.stratum.resilience.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.request.HealthProbePaths;
import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.ResilienceProperties;
import com.exepex.stratum.resilience.RouteLimits;
import com.exepex.stratum.resilience.RouteMeters;
import com.exepex.stratum.resilience.webmvc.ConcurrencyLimitFilter;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Limits the requests each configured route of a Spring MVC application runs at once, unless {@code
 * stratum.resilience.enabled=false}. With no route set, the filter passes every request on at once.
 */
@StratumInternal
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(ResilienceConstants.MODULE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@Import(ResilienceSettingsConfiguration.class)
public final class ServletRouteLimitsAutoConfiguration {

  /**
   * Makes the limit of each configured route.
   *
   * @param settings the resilience settings
   * @return the limits
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RouteLimits stratumRouteLimits(ResilienceProperties settings) {
    return new RouteLimits(settings.incoming());
  }

  /**
   * Holds each request on a limited route within its limit.
   *
   * @param routeLimits the limit of each route
   * @param settings the resilience settings, whose {@code retry-after} a refusal tells the caller
   * @param uncountedPaths the actuator and probe paths, when the service has actuator; none without
   * @param exceptionResolvers Spring MVC's exception resolvers, which answer a refusal
   * @return the filter's registration, after Spring Security and inside Stratum's web filters, for
   *     the first dispatch only
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean(name = "stratumConcurrencyLimitFilter")
  public FilterRegistrationBean<ConcurrencyLimitFilter> stratumConcurrencyLimitFilter(
      RouteLimits routeLimits,
      ResilienceProperties settings,
      @Qualifier(ResilienceConstants.UNCOUNTED_PATHS)
          ObjectProvider<HealthProbePaths> uncountedPaths,
      @Qualifier(ResilienceConstants.HANDLER_EXCEPTION_RESOLVER)
          ObjectProvider<HandlerExceptionResolver> exceptionResolvers) {
    var registration =
        new FilterRegistrationBean<>(
            new ConcurrencyLimitFilter(
                routeLimits,
                uncountedPaths.getIfAvailable(HealthProbePaths::none),
                settings.incoming().retryAfterSeconds(),
                exceptionResolvers));
    registration.setOrder(ConcurrencyLimitFilter.ORDER);
    return registration;
  }

  /**
   * Reads the paths actuator serves on the main port, when the service has actuator. A nested
   * class, so actuator's types are only loaded then.
   */
  @StratumInternal
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(ResilienceConstants.MODULE)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass({
    DispatcherServlet.class,
    DispatcherServletPath.class,
    PathMappedEndpoints.class,
    HealthEndpointProperties.class
  })
  public static class ActuatorPathsConfiguration {

    /**
     * Holds the actuator and probe paths of the main port, which a route never counts.
     *
     * @param environment the service's environment
     * @param servletPath the path of Spring MVC's dispatcher servlet, which serves actuator's
     *     endpoints and probes on the main port, if the service has one
     * @param endpoints the endpoints actuator serves over HTTP, if any
     * @return the paths
     */
    @Bean(ResilienceConstants.UNCOUNTED_PATHS)
    @Fallback
    @ConditionalOnMissingBean(name = ResilienceConstants.UNCOUNTED_PATHS)
    public HealthProbePaths stratumUncountedPaths(
        Environment environment,
        ObjectProvider<DispatcherServletPath> servletPath,
        ObjectProvider<PathMappedEndpoints> endpoints) {
      return HealthProbePaths.onMainPort(
          environment,
          servletPath.stream().map(DispatcherServletPath::getPrefix).findFirst().orElse(""),
          endpoints.stream().flatMap(paths -> paths.getAllPaths().stream()).toList());
    }
  }

  /**
   * Measures each limited route, when the service has Micrometer. A nested class, so Micrometer's
   * types are only loaded then.
   */
  @StratumInternal
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(ResilienceConstants.MODULE)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass({DispatcherServlet.class, MeterBinder.class})
  public static class RouteMetersConfiguration {

    /**
     * Makes the meters of each limited route, which Spring Boot binds to the service's registry.
     *
     * @param routeLimits the limit of each route
     * @return the meters
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public RouteMeters stratumRouteMeters(RouteLimits routeLimits) {
      return new RouteMeters(routeLimits);
    }
  }
}
