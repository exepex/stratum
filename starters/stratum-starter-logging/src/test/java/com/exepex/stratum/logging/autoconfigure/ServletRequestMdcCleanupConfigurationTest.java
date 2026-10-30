package com.exepex.stratum.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.mdc.ServletRequestMdcCleanupFilter;
import com.exepex.stratum.logging.mdc.ServletRequestMdcCleanupListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@DisplayName("A servlet application gets the filter that cleans the MDC after each request")
class ServletRequestMdcCleanupConfigurationTest {

  private static final String MODULE_ON = LoggingConstants.ENABLED + "=true";

  private final WebApplicationContextRunner servletContextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration.class));

  @Test
  @DisplayName(
      "with the module on, a servlet application gets the filter, first in the chain and on"
          + " request, async and error dispatches, and its listener")
  void registersFilterInServletApplications() {
    servletContextRunner
        .withPropertyValues(MODULE_ON)
        .run(
            context -> {
              var registration = context.getBean(FilterRegistrationBean.class);
              assertThat(registration.getFilter())
                  .isInstanceOf(ServletRequestMdcCleanupFilter.class);
              assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
              assertThat(registration.determineDispatcherTypes())
                  .containsExactlyInAnyOrder(
                      DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
              assertThat(context.getBean(ServletListenerRegistrationBean.class).getListener())
                  .isInstanceOf(ServletRequestMdcCleanupListener.class);
            });
  }

  @Test
  @DisplayName("with the module unset or off, there is no filter")
  void moduleOffRegistersNoFilter() {
    servletContextRunner.run(
        context ->
            assertThat(context)
                .doesNotHaveBean(FilterRegistrationBean.class)
                .doesNotHaveBean(ServletListenerRegistrationBean.class));
    servletContextRunner
        .withPropertyValues(LoggingConstants.ENABLED + "=false")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(FilterRegistrationBean.class)
                    .doesNotHaveBean(ServletListenerRegistrationBean.class));
  }

  @Test
  @DisplayName("an application that is not a servlet one, or has no servlet API, gets no filter")
  void noServletApplicationRegistersNoFilter() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration.class))
        .withPropertyValues(MODULE_ON)
        .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    servletContextRunner
        .withPropertyValues(MODULE_ON)
        .withClassLoader(new FilteredClassLoader(Filter.class))
        .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
  }

  @Test
  @DisplayName("the service's own filter bean replaces Stratum's")
  void serviceFilterWins() {
    var serviceFilter = new ServletRequestMdcCleanupFilter();
    servletContextRunner
        .withPropertyValues(MODULE_ON)
        .withBean(ServletRequestMdcCleanupFilter.class, () -> serviceFilter)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
              assertThat(context.getBean(ServletRequestMdcCleanupFilter.class))
                  .isSameAs(serviceFilter);
            });
  }

  @Test
  @DisplayName("the service's own listener registration replaces Stratum's")
  void serviceListenerWins() {
    servletContextRunner
        .withPropertyValues(MODULE_ON)
        .withUserConfiguration(ServiceListenerConfiguration.class)
        .run(
            context ->
                assertThat(context.getBeansOfType(ServletListenerRegistrationBean.class))
                    .containsExactly(
                        Map.entry(
                            "serviceMdcCleanupListener",
                            ServiceListenerConfiguration.REGISTRATION)));
  }

  @Test
  @DisplayName("a company framework's registration with no order wins over Stratum's")
  void companyRegistrationWins() {
    new WebApplicationContextRunner()
        .withPropertyValues(MODULE_ON)
        .withConfiguration(
            AutoConfigurations.of(
                LoggingAutoConfiguration.class, CompanyFilterAutoConfiguration.class))
        .run(
            context ->
                assertThat(context.getBean(FilterRegistrationBean.class))
                    .isSameAs(CompanyFilterAutoConfiguration.REGISTRATION));
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyFilterAutoConfiguration {

    /** The company's registration of the filter. */
    static final FilterRegistrationBean<ServletRequestMdcCleanupFilter> REGISTRATION =
        new FilterRegistrationBean<>(new ServletRequestMdcCleanupFilter());

    /**
     * Returns the company's registration.
     *
     * @return the registration
     */
    @Bean
    FilterRegistrationBean<ServletRequestMdcCleanupFilter> companyMdcCleanupFilter() {
      return REGISTRATION;
    }
  }

  /** A service's configuration with its own registration of the listener. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceListenerConfiguration {

    /** The service's registration of the listener. */
    static final ServletListenerRegistrationBean<ServletRequestMdcCleanupListener> REGISTRATION =
        new ServletListenerRegistrationBean<>(new ServletRequestMdcCleanupListener());

    /**
     * Returns the service's registration.
     *
     * @return the registration
     */
    @Bean
    ServletListenerRegistrationBean<ServletRequestMdcCleanupListener> serviceMdcCleanupListener() {
      return REGISTRATION;
    }
  }
}
