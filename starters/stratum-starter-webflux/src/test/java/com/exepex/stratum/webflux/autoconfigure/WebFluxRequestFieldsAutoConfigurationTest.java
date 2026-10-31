package com.exepex.stratum.webflux.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.RequestFieldsPropagation;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import com.exepex.stratum.webflux.CorrelationIdBaggageDecorator;
import com.exepex.stratum.webflux.CorrelationIdWebFilter;
import com.exepex.stratum.webflux.RequestFieldsWebFilter;
import com.exepex.stratum.webflux.RequestRouteHandlerAdapter;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;

@DisplayName(
    "WebFluxRequestFieldsAutoConfiguration creates the request fields and correlation beans")
class WebFluxRequestFieldsAutoConfigurationTest {

  /** The setting that turns the module on. */
  private static final String MODULE_ON = WebConstants.ENABLED + "=true";

  /** A trusted proxy range whose prefix is longer than its address. */
  private static final String TOO_LONG_RANGE = "10.0.0.0/33";

  /** A reactive application with Stratum's request fields. */
  private final ReactiveWebApplicationContextRunner contextRunner =
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WebFluxRequestFieldsAutoConfiguration.class));

  @Nested
  @DisplayName("when the module is switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, every request fields and correlation bean is created")
    void createsBeans() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(RequestFieldsReader.class)
                      .hasSingleBean(CorrelationIdChooser.class)
                      .hasSingleBean(RequestFieldsPropagation.class)
                      .hasSingleBean(RequestFieldsWebFilter.class)
                      .hasSingleBean(RequestRouteHandlerAdapter.class)
                      .hasSingleBean(CorrelationIdWebFilter.class)
                      .hasSingleBean(CorrelationIdBaggageDecorator.class));
    }

    @Test
    @DisplayName("with the module unset, nothing is created")
    void moduleOffCreatesNothing() {
      contextRunner.run(
          context ->
              assertThat(context)
                  .doesNotHaveBean(RequestFieldsReader.class)
                  .doesNotHaveBean(RequestFieldsWebFilter.class)
                  .doesNotHaveBean(CorrelationIdWebFilter.class));
    }

    @Test
    @DisplayName("an application that is not a reactive web application gets nothing")
    void nonReactiveApplicationGetsNothing() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WebFluxRequestFieldsAutoConfiguration.class))
          .withPropertyValues(MODULE_ON)
          .run(context -> assertThat(context).doesNotHaveBean(RequestFieldsReader.class));
    }

    @Test
    @DisplayName(
        "without Micrometer Tracing, the fields are logged but no correlation ID is chosen")
    void withoutTracingNoCorrelationFilter() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withClassLoader(new FilteredClassLoader(Tracer.class))
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(RequestFieldsWebFilter.class)
                      .doesNotHaveBean(CorrelationIdChooser.class)
                      .doesNotHaveBean(CorrelationIdWebFilter.class)
                      .doesNotHaveBean(CorrelationIdBaggageDecorator.class));
    }

    @Test
    @DisplayName("a trusted proxy range that does not fit its address stops startup")
    void badTrustedProxyStopsStartup() {
      contextRunner
          .withPropertyValues(
              MODULE_ON, WebConstants.CLIENT_IP_TRUSTED_PROXIES + "=" + TOO_LONG_RANGE)
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(StratumException.class)
                      .hasMessageStartingWith("SX-CORE-1003")
                      .hasMessageNotContaining(TOO_LONG_RANGE));
    }
  }

  @Nested
  @DisplayName("when the service or a company framework has its own beans")
  class BackingOff {

    @Test
    @DisplayName("the service's own reader replaces Stratum's")
    void serviceBeanWins() throws IOException {
      try (var serviceReader = defaultReader()) {
        contextRunner
            .withPropertyValues(MODULE_ON)
            .withBean(RequestFieldsReader.class, () -> serviceReader)
            .run(
                context ->
                    assertThat(context.getBean(RequestFieldsReader.class)).isSameAs(serviceReader));
      }
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyBeanWins() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withConfiguration(AutoConfigurations.of(CompanyBeansAutoConfiguration.class))
          .run(
              context ->
                  assertThat(context.getBean(RequestFieldsReader.class))
                      .isSameAs(CompanyBeansAutoConfiguration.READER));
    }
  }

  /** Returns a reader with the default settings. */
  private static RequestFieldsReader defaultReader() {
    return RequestFieldsReader.from(WebProperties.resolve(new MockEnvironment()));
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyBeansAutoConfiguration {

    /** The company's reader. */
    static final RequestFieldsReader READER = defaultReader();

    /**
     * Returns the company's reader.
     *
     * @return the reader
     */
    @Bean
    RequestFieldsReader companyRequestFieldsReader() {
      return READER;
    }
  }
}
