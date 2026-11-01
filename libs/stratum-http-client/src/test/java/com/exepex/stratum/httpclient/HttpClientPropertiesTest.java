package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.testsupport.LargePoolHttpClientSettings;
import com.exepex.stratum.test.StarterTestSupport;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("HttpClientProperties come from properties, changed by Java configuration, which wins")
class HttpClientPropertiesTest {

  /** How every message about a broken setting starts. */
  private static final String INVALID_SETTINGS = "SX-CORE-1003: Invalid settings: ";

  /** The pool size a service gets when it sets none. */
  private static final int DEFAULT_MAX_CONNECTIONS = 50;

  /** The pool size and wait the .properties test sets. */
  private static final int PROPERTIES_MAX_CONNECTIONS = 10;

  private static final Duration PROPERTIES_MAX_WAIT = Duration.ofMillis(750);

  /** The pool size and wait in http-client-yaml.yml. */
  private static final int YAML_MAX_CONNECTIONS = 20;

  private static final Duration YAML_MAX_WAIT = Duration.ofMillis(500);

  @Test
  @DisplayName("with nothing set, each downstream service gets up to 50 connections, waited 2s for")
  void defaults() {
    var pool = HttpClientProperties.resolve(new MockEnvironment()).pool();

    assertThat(pool.maxConnections()).isEqualTo(DEFAULT_MAX_CONNECTIONS);
    assertThat(pool.maxWait()).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  @DisplayName("the pool is read from a .properties file")
  void poolFromProperties() {
    var environment =
        new MockEnvironment()
            .withProperty(
                HttpClientConstants.POOL_MAX_CONNECTIONS,
                String.valueOf(PROPERTIES_MAX_CONNECTIONS))
            .withProperty(HttpClientConstants.POOL_MAX_WAIT, PROPERTIES_MAX_WAIT.toString());

    var pool = HttpClientProperties.resolve(environment).pool();

    assertThat(pool.maxConnections()).isEqualTo(PROPERTIES_MAX_CONNECTIONS);
    assertThat(pool.maxWait()).isEqualTo(PROPERTIES_MAX_WAIT);
  }

  @Test
  @DisplayName("the pool is read from a .yml file")
  void poolFromYaml() throws IOException {
    var environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("http-client-yaml", new ClassPathResource("http-client-yaml.yml"))
        .forEach(environment.getPropertySources()::addFirst);

    var pool = HttpClientProperties.resolve(environment).pool();

    assertThat(pool.maxConnections()).isEqualTo(YAML_MAX_CONNECTIONS);
    assertThat(pool.maxWait()).isEqualTo(YAML_MAX_WAIT);
  }

  @Test
  @DisplayName("a customizer listed in spring.factories wins over the files")
  void javaConfigurationWins(@TempDir Path folder) throws IOException {
    var environment =
        new MockEnvironment().withProperty(HttpClientConstants.POOL_MAX_CONNECTIONS, "10");

    var properties =
        StarterTestSupport.withCustomizers(
            folder,
            HttpClientPropertiesCustomizer.class,
            List.of(LargePoolHttpClientSettings.class),
            () -> HttpClientProperties.resolve(environment));

    assertThat(properties.pool().maxConnections())
        .isEqualTo(LargePoolHttpClientSettings.MAX_CONNECTIONS);
  }

  @Test
  @DisplayName("a pool of no connections stops startup with SX-CORE-1003, naming the key")
  void emptyPoolIsRejected() {
    var environment =
        new MockEnvironment().withProperty(HttpClientConstants.POOL_MAX_CONNECTIONS, "0");

    assertThatExceptionOfType(ConstraintViolationException.class)
        .isThrownBy(() -> HttpClientProperties.resolve(environment))
        .withMessageStartingWith(INVALID_SETTINGS + HttpClientConstants.POOL_MAX_CONNECTIONS);
  }

  @Test
  @DisplayName("a wait of zero stops startup with SX-CORE-1003, naming the key")
  void zeroWaitIsRejected() {
    var environment = new MockEnvironment().withProperty(HttpClientConstants.POOL_MAX_WAIT, "0s");

    assertThatExceptionOfType(ConstraintViolationException.class)
        .isThrownBy(() -> HttpClientProperties.resolve(environment))
        .withMessageStartingWith(INVALID_SETTINGS + HttpClientConstants.POOL_MAX_WAIT);
  }
}
