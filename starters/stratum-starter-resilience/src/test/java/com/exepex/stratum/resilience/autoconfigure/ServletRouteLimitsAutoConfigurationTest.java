package com.exepex.stratum.resilience.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.request.HealthProbePaths;
import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.ResilienceErrorCode;
import com.exepex.stratum.resilience.ResilienceProperties;
import com.exepex.stratum.resilience.ResiliencePropertiesCustomizer;
import com.exepex.stratum.resilience.RouteLimits;
import com.exepex.stratum.resilience.RouteMeters;
import com.exepex.stratum.resilience.testsupport.OrdersResilienceSettings;
import com.exepex.stratum.resilience.testsupport.ResilienceTestData;
import com.exepex.stratum.resilience.webmvc.ConcurrencyLimitFilter;
import com.exepex.stratum.test.ErrorCodeChecks;
import com.exepex.stratum.test.StarterTestSupport;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

@DisplayName("ServletRouteLimitsAutoConfiguration limits the routes of a Spring MVC application")
class ServletRouteLimitsAutoConfigurationTest {

  /** The name of the filter's registration bean. */
  private static final String FILTER = "stratumConcurrencyLimitFilter";

  /** Spring Security's filter order, which the limit must run after. */
  private static final int SPRING_SECURITY_ORDER = -100;

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ServletRouteLimitsAutoConfiguration.class))
          .withPropertyValues(ResilienceTestData.REPORTS_MAX_CONCURRENT + "=10");

  @Nested
  @DisplayName("by default")
  class Defaults {

    @Test
    @DisplayName("the settings, route limits, meters, uncounted paths and filter are created")
    void createsBeans() {
      contextRunner.run(
          context ->
              assertThat(context)
                  .hasSingleBean(ResilienceProperties.class)
                  .hasSingleBean(RouteLimits.class)
                  .hasSingleBean(RouteMeters.class)
                  .hasBean(ResilienceConstants.UNCOUNTED_PATHS)
                  .hasBean(FILTER));
    }

    @Test
    @DisplayName("the filter runs after Spring Security and before Stratum's web filters end")
    void filterRunsAfterSpringSecurity() {
      contextRunner.run(
          context ->
              assertThat(context.getBean(FILTER, FilterRegistrationBean.class).getOrder())
                  .isEqualTo(ConcurrencyLimitFilter.ORDER)
                  .isGreaterThan(SPRING_SECURITY_ORDER));
    }

    @Test
    @DisplayName("the routes come from the properties")
    void routesFromProperties() {
      contextRunner.run(
          context -> assertThat(context.getBean(RouteLimits.class).find("/reports/1")).isPresent());
    }
  }

  @Nested
  @DisplayName("with the module switch")
  class ModuleSwitch {

    @Test
    @DisplayName("on when stratum.resilience.enabled is unset")
    void onWhenUnset() {
      contextRunner.run(context -> assertThat(context).hasBean(FILTER));
    }

    @Test
    @DisplayName("off with stratum.resilience.enabled=false: nothing is created")
    void offWhenFalse() {
      contextRunner
          .withPropertyValues(ResilienceConstants.ENABLED + "=false")
          .run(
              context ->
                  assertThat(context)
                      .doesNotHaveBean(FILTER)
                      .doesNotHaveBean(RouteLimits.class)
                      .doesNotHaveBean(ResilienceProperties.class));
    }
  }

  @Nested
  @DisplayName("when the service or its framework supplies a bean")
  class BackOff {

    @Test
    @DisplayName("the service's own route limits are used")
    void serviceRouteLimits() {
      var serviceLimits = new RouteLimits(ResilienceTestData.incoming(Map.of()).build());
      contextRunner
          .withBean(RouteLimits.class, () -> serviceLimits)
          .run(context -> assertThat(context.getBean(RouteLimits.class)).isSameAs(serviceLimits));
    }

    @Test
    @DisplayName("the service's own filter under the same name replaces Stratum's")
    void serviceFilter() {
      var serviceFilter = new FilterRegistrationBean<>();
      contextRunner
          .withBean(FILTER, FilterRegistrationBean.class, () -> serviceFilter)
          .run(context -> assertThat(context.getBean(FILTER)).isSameAs(serviceFilter));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyRouteLimits() {
      contextRunner
          .withConfiguration(AutoConfigurations.of(CompanyRouteLimitsAutoConfiguration.class))
          .run(
              context ->
                  assertThat(context.getBean(RouteLimits.class))
                      .isSameAs(CompanyRouteLimitsAutoConfiguration.ROUTE_LIMITS));
    }
  }

  @Nested
  @DisplayName("with an optional library missing")
  class MissingLibraries {

    @Test
    @DisplayName("without Spring MVC, nothing is created")
    void withoutSpringMvc() {
      contextRunner
          .withClassLoader(new FilteredClassLoader(DispatcherServlet.class))
          .run(context -> assertThat(context).doesNotHaveBean(FILTER));
    }

    @Test
    @DisplayName("without Micrometer, the filter works with no meters")
    void withoutMicrometer() {
      contextRunner
          .withClassLoader(new FilteredClassLoader(MeterBinder.class))
          .run(context -> assertThat(context).hasBean(FILTER).doesNotHaveBean(RouteMeters.class));
    }

    @Test
    @DisplayName("without actuator, the filter works and no path is left uncounted")
    void withoutActuator() {
      contextRunner
          .withClassLoader(new FilteredClassLoader(PathMappedEndpoints.class))
          .run(
              context ->
                  assertThat(context)
                      .hasBean(FILTER)
                      .doesNotHaveBean(ResilienceConstants.UNCOUNTED_PATHS)
                      .doesNotHaveBean(HealthProbePaths.class));
    }
  }

  @Nested
  @DisplayName("with the settings")
  class Settings {

    @Test
    @DisplayName("a bad value stops startup with SX-CORE-1003")
    void badValueStopsStartup() {
      contextRunner
          .withPropertyValues(ResilienceConstants.RETRY_AFTER + "=0s")
          .run(
              context ->
                  assertThat(
                          ErrorCodeChecks.startupFailureOf(context, CoreErrorCode.INVALID_SETTINGS)
                              .getMessage())
                      .contains("retry-after-in-range"));
    }

    @Test
    @DisplayName("a pattern that is not valid stops startup with SX-RESILIENCE-1001")
    void invalidPatternStopsStartup() {
      contextRunner
          .withPropertyValues(ResilienceConstants.INCOMING_ROUTES + "[/api/{id].max-concurrent=1")
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .hasStackTraceContaining(
                          ResilienceErrorCode.INVALID_ROUTE_PATTERN.getCode() + ": "));
    }

    @Test
    @DisplayName(ResilienceTestData.JAVA_CONFIGURATION_WINS)
    void javaConfigurationWins(@TempDir Path folder) throws IOException {
      var reportsLimit = new AtomicInteger();

      StarterTestSupport.withCustomizers(
          folder,
          ResiliencePropertiesCustomizer.class,
          List.of(OrdersResilienceSettings.class),
          () -> contextRunner.run(context -> reportsLimit.set(reportsLimitOf(context))));

      assertThat(reportsLimit).hasValue(OrdersResilienceSettings.REPORTS_LIMIT);
    }

    /** Returns the limit of the reports route in the context's settings. */
    private int reportsLimitOf(ApplicationContext context) {
      return context
          .getBean(ResilienceProperties.class)
          .incoming()
          .routes()
          .get(ResilienceTestData.REPORTS_ROUTE)
          .maxConcurrent();
    }

    @Test
    @DisplayName("a customizer declared as a bean stops startup with SX-CORE-1004")
    void customizerBeanStopsStartup() {
      contextRunner
          .withBean(OrdersResilienceSettings.class)
          .run(
              context ->
                  assertThat(
                          ErrorCodeChecks.startupFailureOf(
                                  context, CoreErrorCode.CUSTOMIZER_DECLARED_AS_BEAN)
                              .getMessage())
                      .contains(ResiliencePropertiesCustomizer.class.getSimpleName()));
    }
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyRouteLimitsAutoConfiguration {

    /** The company's route limits. */
    static final RouteLimits ROUTE_LIMITS =
        new RouteLimits(ResilienceTestData.incoming(Map.of()).build());

    /**
     * Returns the company's route limits.
     *
     * @return the limits
     */
    @Bean
    RouteLimits companyRouteLimits() {
      return ROUTE_LIMITS;
    }
  }
}
