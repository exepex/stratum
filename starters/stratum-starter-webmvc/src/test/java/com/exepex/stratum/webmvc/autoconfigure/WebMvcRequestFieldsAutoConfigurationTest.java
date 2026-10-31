package com.exepex.stratum.webmvc.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.RequestFieldsPropagation;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.RequestLogWriter;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import com.exepex.stratum.webmvc.RequestLogFilter;
import com.exepex.stratum.webmvc.RequestRouteInterceptor;
import com.exepex.stratum.webmvc.ResponseTimeoutFilter;
import com.exepex.stratum.webmvc.ResponseTimeoutInterceptor;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;

@DisplayName(
    "WebMvcRequestFieldsAutoConfiguration creates the request fields and correlation beans")
class WebMvcRequestFieldsAutoConfigurationTest {

  /** The setting that turns the module on. */
  private static final String MODULE_ON = WebConstants.ENABLED + "=true";

  /** The name of the request fields filter's registration. */
  private static final String FIELDS_FILTER = "stratumRequestFieldsFilter";

  /** The name of the correlation ID filter's registration. */
  private static final String CORRELATION_FILTER = "stratumCorrelationIdFilter";

  /** The name of the filter that writes a caller's correlation ID into the baggage. */
  private static final String BAGGAGE_FILTER = "stratumCorrelationIdBaggageFilter";

  /** The name of the request log filter's registration. */
  private static final String REQUEST_LOG_FILTER = "stratumRequestLogFilter";

  /** The name of the timeout filter's registration. */
  private static final String TIMEOUT_FILTER = "stratumResponseTimeoutFilter";

  /** A trusted proxy range whose prefix is longer than its address. */
  private static final String TOO_LONG_RANGE = "10.0.0.0/33";

  /** A servlet application with Stratum's request fields. */
  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WebMvcRequestFieldsAutoConfiguration.class));

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
                      .hasSingleBean(RequestRouteInterceptor.class)
                      .hasSingleBean(ResponseTimeouts.class)
                      .hasSingleBean(ResponseTimeoutInterceptor.class)
                      .hasBean(TIMEOUT_FILTER)
                      .hasBean(FIELDS_FILTER)
                      .hasBean(CORRELATION_FILTER)
                      .hasBean(BAGGAGE_FILTER)
                      .hasSingleBean(RequestLogWriter.class)
                      .hasBean(REQUEST_LOG_FILTER));
    }

    @Test
    @DisplayName("with the module unset, nothing is created")
    void moduleOffCreatesNothing() {
      contextRunner.run(
          context ->
              assertThat(context)
                  .doesNotHaveBean(RequestFieldsReader.class)
                  .doesNotHaveBean(FIELDS_FILTER)
                  .doesNotHaveBean(CORRELATION_FILTER));
    }

    @Test
    @DisplayName("an application that is not a servlet web application gets nothing")
    void nonServletApplicationGetsNothing() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WebMvcRequestFieldsAutoConfiguration.class))
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
                      .hasBean(FIELDS_FILTER)
                      .doesNotHaveBean(CorrelationIdChooser.class)
                      .doesNotHaveBean(CORRELATION_FILTER)
                      .doesNotHaveBean(BAGGAGE_FILTER));
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
    @DisplayName("the service's own request log writer and filter replace Stratum's")
    void serviceRequestLogBeansWin() throws IOException {
      try (var reader = defaultReader()) {
        var serviceWriter =
            new RequestLogWriter(WebProperties.resolve(new MockEnvironment()), reader);
        var serviceFilter = new FilterRegistrationBean<>(new RequestLogFilter(serviceWriter));
        contextRunner
            .withPropertyValues(MODULE_ON)
            .withBean(RequestLogWriter.class, () -> serviceWriter)
            .withBean(REQUEST_LOG_FILTER, FilterRegistrationBean.class, () -> serviceFilter)
            .run(
                context ->
                    assertThat(
                            List.of(
                                context.getBean(RequestLogWriter.class),
                                context.getBean(REQUEST_LOG_FILTER)))
                        .containsExactly(serviceWriter, serviceFilter));
      }
    }

    @Test
    @DisplayName("the service's own timeouts and timeout filter replace Stratum's")
    void serviceTimeoutBeansWin() {
      try (var serviceTimeouts =
          new ResponseTimeouts(WebProperties.resolve(new MockEnvironment()))) {
        var serviceFilter =
            new FilterRegistrationBean<>(new ResponseTimeoutFilter(serviceTimeouts));
        contextRunner
            .withPropertyValues(MODULE_ON)
            .withBean(ResponseTimeouts.class, () -> serviceTimeouts)
            .withBean(TIMEOUT_FILTER, FilterRegistrationBean.class, () -> serviceFilter)
            .run(
                context ->
                    assertThat(
                            List.of(
                                context.getBean(ResponseTimeouts.class),
                                context.getBean(TIMEOUT_FILTER)))
                        .containsExactly(serviceTimeouts, serviceFilter));
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
