package com.exepex.stratum.webclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import com.exepex.stratum.webclient.WebClientConnectionPool;
import com.exepex.stratum.webclient.WebClientGroupSettings;
import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.reactive.ReactiveHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import reactor.netty.http.client.HttpClient;

@DisplayName("WebClientConnectionPoolAutoConfiguration gives the reactive clients a bounded pool")
class WebClientConnectionPoolAutoConfigurationTest {

  /** The setting that turns the module on. */
  private static final String MODULE_ON = HttpClientConstants.ENABLED + "=true";

  /** A pool a service or company framework sets up itself. */
  private static final WebClientConnectionPool OWN_POOL =
      new WebClientConnectionPool(
          HttpClientProperties.PoolSettings.builder()
              .maxConnections(1)
              .maxWait(Duration.ofSeconds(1))
              .build(),
          false);

  /** An application with only this auto-configuration. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ReactiveHttpClientAutoConfiguration.class,
                  HttpServiceClientPropertiesAutoConfiguration.class,
                  WebClientConnectionPoolAutoConfiguration.class));

  @Nested
  @DisplayName("when the module is switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, the pool is bounded and each group keeps its own settings")
    void createsPoolAndGroupSettings() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(WebClientConnectionPool.class)
                      .hasSingleBean(WebClientGroupSettings.class));
    }

    @Test
    @DisplayName("with the module unset or off, nothing is set up")
    void moduleOffCreatesNothing() {
      contextRunner.run(context -> assertNothingSetUp(context));
      contextRunner
          .withPropertyValues(HttpClientConstants.ENABLED + "=false")
          .run(context -> assertNothingSetUp(context));
    }

    @Test
    @DisplayName("without Reactor Netty, nothing is set up")
    void withoutReactorNetty() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withClassLoader(new FilteredClassLoader(HttpClient.class))
          .run(context -> assertNothingSetUp(context));
    }
  }

  @Nested
  @DisplayName("when the service or a company framework has its own pool")
  class BackingOff {

    @Test
    @DisplayName("the service's own pool replaces Stratum's")
    void serviceBeanWins() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withBean(WebClientConnectionPool.class, () -> OWN_POOL)
          .run(
              context ->
                  assertThat(context.getBean(WebClientConnectionPool.class)).isSameAs(OWN_POOL));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyBeanWins() {
      new ApplicationContextRunner()
          .withPropertyValues(MODULE_ON)
          .withConfiguration(
              AutoConfigurations.of(
                  WebClientConnectionPoolAutoConfiguration.class,
                  CompanyPoolAutoConfiguration.class))
          .run(
              context ->
                  assertThat(context.getBean(WebClientConnectionPool.class)).isSameAs(OWN_POOL));
    }
  }

  @Test
  @DisplayName("a pool of no connections stops startup")
  void badSettingStopsStartup() {
    contextRunner
        .withPropertyValues(MODULE_ON, HttpClientConstants.POOL_MAX_CONNECTIONS + "=0")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(ConstraintViolationException.class));
  }

  @Test
  @DisplayName("an HTTP client customizer declared as a bean stops startup with SX-CORE-1004")
  void customizerBeanStopsStartup() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withBean(
            "httpClientCustomizer",
            HttpClientPropertiesCustomizer.class,
            () -> settings -> settings)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(StratumException.class)
                    .hasMessage(
                        "SX-CORE-1004: "
                            + CoreErrorCode.CUSTOMIZER_DECLARED_AS_BEAN.getDescription()
                            + ": customizer beans httpClientCustomizer"));
  }

  /** Checks that the context has none of Stratum's beans. */
  private static void assertNothingSetUp(AssertableApplicationContext context) {
    assertThat(context)
        .doesNotHaveBean(WebClientConnectionPool.class)
        .doesNotHaveBean(WebClientGroupSettings.class);
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyPoolAutoConfiguration {

    /**
     * Returns the company's pool.
     *
     * @return the pool
     */
    @Bean
    WebClientConnectionPool companyConnectionPool() {
      return OWN_POOL;
    }
  }
}
