package com.exepex.stratum.webmvc.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.RequestBodyLimits;
import com.exepex.stratum.web.RequestFieldsPropagation;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.RequestLogWriter;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import com.exepex.stratum.webmvc.RequestBodyLimitFilter;
import com.exepex.stratum.webmvc.RequestBodyLimitInterceptor;
import com.exepex.stratum.webmvc.RequestLogFilter;
import com.exepex.stratum.webmvc.RequestRouteInterceptor;
import com.exepex.stratum.webmvc.ResponseTimeoutFilter;
import com.exepex.stratum.webmvc.ResponseTimeoutInterceptor;
import com.exepex.stratum.webmvc.ServletRequestMdcCleanupFilter;
import com.exepex.stratum.webmvc.ServletRequestMdcCleanupListener;
import com.exepex.stratum.webmvc.testsupport.WebMvcRequestFieldsRunners;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.ApplicationContextAssertProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
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

  /** The name of the body limit filter's registration. */
  private static final String BODY_LIMIT_FILTER = "stratumRequestBodyLimitFilter";

  /** The name of the registration of the filter that cleans the logging context. */
  private static final String MDC_CLEANUP_FILTER = "stratumMdcCleanupFilter";

  /** The name of the registration of the listener that cleans it after a failed request. */
  private static final String MDC_CLEANUP_LISTENER = "stratumMdcCleanupListener";

  /** A trusted proxy range whose prefix is longer than its address. */
  private static final String TOO_LONG_RANGE = "10.0.0.0/33";

  /** A servlet application with Stratum's request fields. */
  private final WebApplicationContextRunner contextRunner =
      WebMvcRequestFieldsRunners.requestFieldsRunner();

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
                      .hasSingleBean(RequestBodyLimits.class)
                      .hasSingleBean(RequestBodyLimitInterceptor.class)
                      .hasBean(BODY_LIMIT_FILTER)
                      .hasBean(FIELDS_FILTER)
                      .hasBean(CORRELATION_FILTER)
                      .hasBean(BAGGAGE_FILTER)
                      .hasSingleBean(RequestLogWriter.class)
                      .hasBean(REQUEST_LOG_FILTER)
                      .hasBean(MDC_CLEANUP_FILTER)
                      .hasBean(MDC_CLEANUP_LISTENER));
    }

    @Test
    @DisplayName("with the module unset or off, nothing is created")
    void moduleOffCreatesNothing() {
      contextRunner.run(context -> assertNothingCreated(context));
      contextRunner
          .withPropertyValues(WebConstants.ENABLED + "=false")
          .run(context -> assertNothingCreated(context));
    }

    @Test
    @DisplayName("an application that is not a servlet web application gets nothing")
    void nonServletApplicationGetsNothing() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WebMvcRequestFieldsAutoConfiguration.class))
          .withPropertyValues(MODULE_ON)
          .run(context -> assertNothingCreated(context));
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
    @DisplayName("the service's own body limits and body limit filter replace Stratum's")
    void serviceBodyLimitBeansWin() {
      var serviceLimits = new RequestBodyLimits(WebProperties.resolve(new MockEnvironment()));
      var serviceFilter = new FilterRegistrationBean<>(new RequestBodyLimitFilter(serviceLimits));
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withBean(RequestBodyLimits.class, () -> serviceLimits)
          .withBean(BODY_LIMIT_FILTER, FilterRegistrationBean.class, () -> serviceFilter)
          .run(
              context ->
                  assertThat(
                          List.of(
                              context.getBean(RequestBodyLimits.class),
                              context.getBean(BODY_LIMIT_FILTER)))
                      .containsExactly(serviceLimits, serviceFilter));
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

  @Nested
  @DisplayName("when each request ends")
  class LoggingContextCleanup {

    @Test
    @DisplayName(
        "the cleanup filter runs first in the chain, on request, async and error dispatches, and"
            + " its listener is registered")
    void registersCleanupFirstOnEveryDispatch() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .run(
              context -> {
                var registration =
                    context.getBean(MDC_CLEANUP_FILTER, FilterRegistrationBean.class);
                assertThat(registration.getFilter())
                    .isInstanceOf(ServletRequestMdcCleanupFilter.class);
                assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
                assertThat(registration.determineDispatcherTypes())
                    .containsExactlyInAnyOrder(
                        DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
                assertThat(
                        context
                            .getBean(MDC_CLEANUP_LISTENER, ServletListenerRegistrationBean.class)
                            .getListener())
                    .isInstanceOf(ServletRequestMdcCleanupListener.class);
              });
    }

    @Test
    @DisplayName("the service's own cleanup filter and listener, by name, replace Stratum's")
    void serviceCleanupBeansWin() {
      var serviceFilter = new FilterRegistrationBean<>(new ServletRequestMdcCleanupFilter());
      var serviceListener =
          new ServletListenerRegistrationBean<>(new ServletRequestMdcCleanupListener());
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withBean(MDC_CLEANUP_FILTER, FilterRegistrationBean.class, () -> serviceFilter)
          .withBean(
              MDC_CLEANUP_LISTENER, ServletListenerRegistrationBean.class, () -> serviceListener)
          .run(
              context ->
                  assertThat(
                          List.of(
                              context.getBean(MDC_CLEANUP_FILTER),
                              context.getBean(MDC_CLEANUP_LISTENER)))
                      .containsExactly(serviceFilter, serviceListener));
    }

    @Test
    @DisplayName("a company framework's cleanup beans, with no order, win over Stratum's")
    void companyCleanupBeansWin() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withConfiguration(AutoConfigurations.of(CompanyBeansAutoConfiguration.class))
          .run(
              context ->
                  assertThat(
                          List.of(
                              context.getBean(MDC_CLEANUP_FILTER),
                              context.getBean(MDC_CLEANUP_LISTENER)))
                      .containsExactly(
                          CompanyBeansAutoConfiguration.CLEANUP_FILTER,
                          CompanyBeansAutoConfiguration.CLEANUP_LISTENER));
    }
  }

  /** Checks that none of the module's beans exists. */
  private <C extends ApplicationContext> void assertNothingCreated(
      ApplicationContextAssertProvider<C> context) {
    assertThat(context)
        .doesNotHaveBean(RequestFieldsReader.class)
        .doesNotHaveBean(FIELDS_FILTER)
        .doesNotHaveBean(CORRELATION_FILTER)
        .doesNotHaveBean(MDC_CLEANUP_FILTER)
        .doesNotHaveBean(MDC_CLEANUP_LISTENER);
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

    /** The company's registration of the cleanup filter. */
    static final FilterRegistrationBean<ServletRequestMdcCleanupFilter> CLEANUP_FILTER =
        new FilterRegistrationBean<>(new ServletRequestMdcCleanupFilter());

    /** The company's registration of the cleanup listener. */
    static final ServletListenerRegistrationBean<ServletRequestMdcCleanupListener>
        CLEANUP_LISTENER =
            new ServletListenerRegistrationBean<>(new ServletRequestMdcCleanupListener());

    /**
     * Returns the company's registration of the cleanup filter.
     *
     * @return the registration
     */
    @Bean
    FilterRegistrationBean<ServletRequestMdcCleanupFilter> stratumMdcCleanupFilter() {
      return CLEANUP_FILTER;
    }

    /**
     * Returns the company's registration of the cleanup listener.
     *
     * @return the registration
     */
    @Bean
    ServletListenerRegistrationBean<ServletRequestMdcCleanupListener> stratumMdcCleanupListener() {
      return CLEANUP_LISTENER;
    }
  }
}
