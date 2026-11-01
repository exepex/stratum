package com.exepex.stratum.restclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import com.exepex.stratum.restclient.RestClientConnectionPool;
import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

@DisplayName("RestClientConnectionPoolAutoConfiguration bounds the blocking clients' pool")
class RestClientConnectionPoolAutoConfigurationTest {

  /** The setting that turns the module on. */
  private static final String MODULE_ON = HttpClientConstants.ENABLED + "=true";

  /** A pool a service or company framework sets up itself. */
  private static final RestClientConnectionPool OWN_POOL =
      new RestClientConnectionPool(
          HttpClientProperties.PoolSettings.builder()
              .maxConnections(1)
              .maxWait(Duration.ofSeconds(1))
              .build());

  /** An application with only this auto-configuration. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(RestClientConnectionPoolAutoConfiguration.class));

  @Nested
  @DisplayName("when the module is switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, the pool is bounded")
    void createsPool() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .run(context -> assertThat(context).hasSingleBean(RestClientConnectionPool.class));
    }

    @Test
    @DisplayName("with the module unset or off, nothing is set up")
    void moduleOffCreatesNothing() {
      contextRunner.run(
          context -> assertThat(context).doesNotHaveBean(RestClientConnectionPool.class));
      contextRunner
          .withPropertyValues(HttpClientConstants.ENABLED + "=false")
          .run(context -> assertThat(context).doesNotHaveBean(RestClientConnectionPool.class));
    }

    @Test
    @DisplayName("without Apache HttpClient 5, nothing is set up")
    void withoutApacheHttpClient() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withClassLoader(new FilteredClassLoader(HttpClientBuilder.class))
          .run(context -> assertThat(context).doesNotHaveBean(RestClientConnectionPool.class));
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
          .withBean(RestClientConnectionPool.class, () -> OWN_POOL)
          .run(
              context ->
                  assertThat(context.getBean(RestClientConnectionPool.class)).isSameAs(OWN_POOL));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyBeanWins() {
      new ApplicationContextRunner()
          .withPropertyValues(MODULE_ON)
          .withConfiguration(
              AutoConfigurations.of(
                  RestClientConnectionPoolAutoConfiguration.class,
                  CompanyPoolAutoConfiguration.class))
          .run(
              context ->
                  assertThat(context.getBean(RestClientConnectionPool.class)).isSameAs(OWN_POOL));
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

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyPoolAutoConfiguration {

    /**
     * Returns the company's pool.
     *
     * @return the pool
     */
    @Bean
    RestClientConnectionPool companyConnectionPool() {
      return OWN_POOL;
    }
  }
}
