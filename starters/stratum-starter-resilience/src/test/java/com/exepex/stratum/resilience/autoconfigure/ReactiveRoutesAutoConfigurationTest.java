package com.exepex.stratum.resilience.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.resilience.ReactiveRoutesCheck;
import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.ResilienceErrorCode;
import com.exepex.stratum.resilience.ResilienceProperties;
import com.exepex.stratum.resilience.RouteLimits;
import com.exepex.stratum.resilience.testsupport.ResilienceTestData;
import com.exepex.stratum.test.ErrorCodeChecks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

@DisplayName("Each protection switches on only where its kind of incoming work exists")
class ReactiveRoutesAutoConfigurationTest {

  /** Both of the module's auto-configurations, as a service gets them. */
  private static final AutoConfigurations RESILIENCE =
      AutoConfigurations.of(
          ServletRouteLimitsAutoConfiguration.class, ReactiveRoutesAutoConfiguration.class);

  /** A route a service sets. */
  private static final String ROUTE = ResilienceTestData.REPORTS_MAX_CONCURRENT + "=5";

  @Nested
  @DisplayName("in a reactive web application")
  class Reactive {

    private final ReactiveWebApplicationContextRunner contextRunner =
        new ReactiveWebApplicationContextRunner().withConfiguration(RESILIENCE);

    @Test
    @DisplayName("routes stop startup with SX-RESILIENCE-1002, naming issue #420")
    void routesStopStartup() {
      contextRunner
          .withPropertyValues(ROUTE)
          .run(
              context ->
                  assertThat(
                          ErrorCodeChecks.startupFailureOf(
                                  context, ResilienceErrorCode.ROUTES_IN_REACTIVE_APPLICATION)
                              .getMessage())
                      .contains("issues/420"));
    }

    @Test
    @DisplayName("without routes it starts, with the check and no servlet filter")
    void startsWithoutRoutes() {
      contextRunner.run(
          context ->
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ReactiveRoutesCheck.class)
                  .doesNotHaveBean(RouteLimits.class));
    }

    @Test
    @DisplayName("with the module off, routes do not stop startup")
    void moduleOff() {
      contextRunner
          .withPropertyValues(ROUTE, ResilienceConstants.ENABLED + "=false")
          .run(context -> assertThat(context).hasNotFailed());
    }
  }

  @Nested
  @DisplayName("in an application with no web server")
  class NoWebServer {

    @Test
    @DisplayName("routes are inert: it starts, reads no setting and creates nothing")
    void routesInert() {
      new ApplicationContextRunner()
          .withConfiguration(RESILIENCE)
          .withPropertyValues(ROUTE, ResilienceConstants.RETRY_AFTER + "=0s")
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .doesNotHaveBean(ResilienceProperties.class)
                      .doesNotHaveBean(RouteLimits.class)
                      .doesNotHaveBean(ReactiveRoutesCheck.class));
    }
  }
}
