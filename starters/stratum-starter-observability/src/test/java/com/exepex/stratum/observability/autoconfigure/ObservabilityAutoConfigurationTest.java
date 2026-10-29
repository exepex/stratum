package com.exepex.stratum.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.SpanUrlFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.util.UriComponentsBuilder;

@DisplayName("ObservabilityAutoConfiguration creates the span URL filter")
class ObservabilityAutoConfigurationTest {

  private static final String MODULE_ON = ObservabilityConstants.ENABLED + "=true";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

  @Test
  @DisplayName("with the module on, the filter is created")
  void createsFilter() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .run(context -> assertThat(context).hasSingleBean(SpanUrlFilter.class));
  }

  @Test
  @DisplayName("with the module unset or off, nothing is created")
  void moduleOffCreatesNothing() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(SpanUrlFilter.class));
    contextRunner
        .withPropertyValues(ObservabilityConstants.ENABLED + "=false")
        .run(context -> assertThat(context).doesNotHaveBean(SpanUrlFilter.class));
  }

  @Test
  @DisplayName("without Spring's web classes, which make HTTP client spans, nothing is created")
  void withoutSpringWebCreatesNothing() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withClassLoader(new FilteredClassLoader(UriComponentsBuilder.class))
        .run(context -> assertThat(context).doesNotHaveBean(SpanUrlFilter.class));
  }

  @Test
  @DisplayName("the service's own filter replaces Stratum's")
  void serviceFilterWins() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withUserConfiguration(ServiceFilterConfiguration.class)
        .run(
            context ->
                assertThat(context)
                    .getBean(SpanUrlFilter.class)
                    .isSameAs(context.getBean(ServiceFilterConfiguration.class).filter));
  }

  @Test
  @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
  void companyFilterWins() {
    new ApplicationContextRunner()
        .withPropertyValues(MODULE_ON)
        .withConfiguration(
            AutoConfigurations.of(
                ObservabilityAutoConfiguration.class, CompanyFilterAutoConfiguration.class))
        .run(
            context ->
                assertThat(context)
                    .getBean(SpanUrlFilter.class)
                    .isSameAs(CompanyFilterAutoConfiguration.FILTER));
  }

  /** A service's configuration with its own filter. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceFilterConfiguration {

    /** The service's filter. */
    final SpanUrlFilter filter = new SpanUrlFilter();

    /**
     * Returns the service's filter.
     *
     * @return the filter
     */
    @Bean
    SpanUrlFilter serviceSpanUrlFilter() {
      return filter;
    }
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyFilterAutoConfiguration {

    /** The company's filter. */
    static final SpanUrlFilter FILTER = new SpanUrlFilter();

    /**
     * Returns the company's filter.
     *
     * @return the filter
     */
    @Bean
    SpanUrlFilter companySpanUrlFilter() {
      return FILTER;
    }
  }
}
