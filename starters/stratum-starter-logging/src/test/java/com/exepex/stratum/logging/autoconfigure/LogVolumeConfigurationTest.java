package com.exepex.stratum.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.DuplicateMessageFilter;
import ch.qos.logback.classic.turbo.TurboFilter;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.testsupport.LogVolumeTestData;
import com.exepex.stratum.logging.volume.LogVolumeFilter;
import com.exepex.stratum.logging.volume.LogVolumeFilterRegistration;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

@DisplayName("LoggingAutoConfiguration adds the volume filter to Logback while the service runs")
class LogVolumeConfigurationTest {

  /** A service's own filter, which replaces Stratum's. */
  private static final LogVolumeFilter SERVICE_FILTER =
      new LogVolumeFilter(LogVolumeTestData.defaults().build(), Clock.systemUTC());

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration.class));

  /** Checks that every application removed its filter when it stopped. */
  @AfterEach
  void noFilterIsLeft() {
    assertThat(LogVolumeTestData.volumeFiltersInLogback()).isEmpty();
  }

  @Nested
  @DisplayName("with the module on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, Stratum's filter is in Logback until the service stops")
    void addsTheFilterWhileRunning() {
      contextRunner.run(
          context ->
              assertThat(LogVolumeTestData.volumeFiltersInLogback())
                  .containsExactly(context.getBean(LogVolumeFilter.class)));
    }

    @Test
    @DisplayName("with lazy initialization on, the filter is still added at startup")
    void lazyInitializationStillAddsTheFilter() {
      contextRunner
          .withInitializer(
              context ->
                  context.addBeanFactoryPostProcessor(
                      new LazyInitializationBeanFactoryPostProcessor()))
          .run(_ -> assertThat(LogVolumeTestData.volumeFiltersInLogback()).hasSize(1));
    }

    @Test
    @DisplayName("with the module off, there is no filter")
    void moduleOffAddsNoFilter() {
      contextRunner
          .withPropertyValues(LoggingConstants.ENABLED + "=false")
          .run(
              context -> {
                assertThat(context).doesNotHaveBean(LogVolumeFilter.class);
                assertThat(LogVolumeTestData.volumeFiltersInLogback()).isEmpty();
              });
    }

    @Test
    @DisplayName("with the limits off, the filter is not added")
    void limitsOffAddNoFilter() {
      contextRunner
          .withPropertyValues(LoggingConstants.VOLUME + ".enabled=false")
          .run(_ -> assertThat(LogVolumeTestData.volumeFiltersInLogback()).isEmpty());
    }

    @Test
    @DisplayName("without Logback, there is no filter")
    void withoutLogbackCreatesNoFilter() {
      contextRunner
          .withClassLoader(new FilteredClassLoader(LoggerContext.class))
          .run(
              context ->
                  assertThat(context)
                      .doesNotHaveBean(LogVolumeFilter.class)
                      .doesNotHaveBean(LogVolumeFilterRegistration.class));
    }
  }

  @Nested
  @DisplayName("when the service or a company framework has its own beans")
  class BackingOff {

    @Test
    @DisplayName("the service's own volume filter replaces Stratum's and is the one added")
    void serviceFilterWins() {
      contextRunner
          .withBean(LogVolumeFilter.class, () -> SERVICE_FILTER)
          .run(
              _ ->
                  assertThat(LogVolumeTestData.volumeFiltersInLogback())
                      .containsExactly(SERVICE_FILTER));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyFilterWins() {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  LoggingAutoConfiguration.class, CompanyVolumeAutoConfiguration.class))
          .run(
              _ ->
                  assertThat(LogVolumeTestData.volumeFiltersInLogback())
                      .containsExactly(SERVICE_FILTER));
    }

    @Test
    @DisplayName("an unrelated Logback filter bean leaves Stratum's limits on")
    void unrelatedTurboFilterKeepsTheLimits() {
      contextRunner
          .withBean(TurboFilter.class, DuplicateMessageFilter::new)
          .run(_ -> assertThat(LogVolumeTestData.volumeFiltersInLogback()).hasSize(1));
    }
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyVolumeAutoConfiguration {

    /**
     * Returns the company's filter.
     *
     * @return the filter
     */
    @Bean
    LogVolumeFilter companyLogVolumeFilter() {
      return SERVICE_FILTER;
    }
  }
}
