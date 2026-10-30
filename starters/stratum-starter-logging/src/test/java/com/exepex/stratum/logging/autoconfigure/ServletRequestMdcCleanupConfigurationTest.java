package com.exepex.stratum.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.mdc.ServletRequestMdcCleanupFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

@DisplayName("A servlet application gets the filter that cleans the MDC after each request")
class ServletRequestMdcCleanupConfigurationTest {

  private static final String MODULE_ON = LoggingConstants.ENABLED + "=true";

  private final WebApplicationContextRunner servletContextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration.class));

  @Test
  @DisplayName("with the module on, a servlet application gets the filter")
  void registersFilterInServletApplications() {
    servletContextRunner
        .withPropertyValues(MODULE_ON)
        .run(
            context ->
                assertThat(context.getBean(FilterRegistrationBean.class).getFilter())
                    .isInstanceOf(ServletRequestMdcCleanupFilter.class));
  }

  @Test
  @DisplayName("with the module unset or off, there is no filter")
  void moduleOffRegistersNoFilter() {
    servletContextRunner.run(
        context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    servletContextRunner
        .withPropertyValues(LoggingConstants.ENABLED + "=false")
        .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
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
}
