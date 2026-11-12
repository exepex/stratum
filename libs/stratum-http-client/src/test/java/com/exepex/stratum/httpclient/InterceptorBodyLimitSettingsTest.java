package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.LargeBodySettings;
import com.exepex.stratum.httpclient.testsupport.SettingsChecks;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

@DisplayName("interceptor-body-limit comes from the files and Java, and is refused out of range")
class InterceptorBodyLimitSettingsTest {

  /** The inventory client's own limit, as in {@code http-client.yml}. */
  private static final DataSize CLIENT_LIMIT = DataSize.parse("8MB");

  /** The shared limit, as in {@code http-client.yml}. */
  private static final DataSize SHARED_LIMIT = DataSize.parse("2MB");

  /** The shared limit's key. */
  private static final String DEFAULT_LIMIT =
      HttpClientConstants.DEFAULTS + ".interceptor-body-limit";

  /** The inventory client's limit key. */
  private static final String INVENTORY_LIMIT =
      HttpClientTestData.INVENTORY_SETTINGS + ".interceptor-body-limit";

  @Nested
  @DisplayName("from properties, YAML and Java")
  class Binding {

    @Test
    @DisplayName("a client's own limit wins over the shared one, from properties")
    void clientWinsFromProperties() {
      var environment =
          new MockEnvironment()
              .withProperty(DEFAULT_LIMIT, "2MB")
              .withProperty(INVENTORY_LIMIT, "8MB");

      var properties = HttpClientProperties.resolve(environment);

      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).interceptorBodyLimit())
          .isEqualTo(CLIENT_LIMIT);
      assertThat(properties.clientSettings(HttpClientTestData.PARTNER).interceptorBodyLimit())
          .isEqualTo(SHARED_LIMIT);
    }

    @Test
    @DisplayName("a client's own limit wins over the shared one, from a .yml file")
    void clientWinsFromYaml() throws IOException {
      var properties = HttpClientProperties.resolve(HttpClientTestData.yamlEnvironment());

      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).interceptorBodyLimit())
          .isEqualTo(CLIENT_LIMIT);
      assertThat(properties.clientSettings(HttpClientTestData.PARTNER).interceptorBodyLimit())
          .isEqualTo(SHARED_LIMIT);
    }

    @Test
    @DisplayName(HttpClientTestData.JAVA_CONFIGURATION_WINS)
    void javaWinsOverFiles(@TempDir Path folder) throws IOException {
      var environment = new MockEnvironment().withProperty(INVENTORY_LIMIT, "8MB");

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              HttpClientPropertiesCustomizer.class,
              List.of(LargeBodySettings.class),
              () -> HttpClientProperties.resolve(environment));

      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).interceptorBodyLimit())
          .isEqualTo(LargeBodySettings.LIMIT);
    }
  }

  @Nested
  @DisplayName("out of range, from 1 byte to 1 gigabyte")
  class Range {

    /** Limits that stop startup: 0, a negative one, and 1 byte over 1 GB. */
    private static final List<String> REFUSED = List.of("0", "-1B", "1073741825B");

    /** The smallest and the largest limit allowed. */
    private static final List<String> ACCEPTED = List.of("1B", "1GB");

    @ParameterizedTest(name = "{0}")
    @FieldSource("REFUSED")
    @DisplayName("a client's limit stops startup, naming the key, never the value or the client")
    void clientLimitRefused(String limit) {
      SettingsChecks.assertSettingsInvalid(
          "clients[*].interceptor-body-limit-in-range", INVENTORY_LIMIT + "=" + limit);
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("REFUSED")
    @DisplayName("a shared limit stops startup, naming the key, never the value")
    void sharedLimitRefused(String limit) {
      SettingsChecks.assertSettingsInvalid(
          "defaults.interceptor-body-limit-in-range", DEFAULT_LIMIT + "=" + limit);
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("ACCEPTED")
    @DisplayName("a limit at either end of the range is accepted")
    void edgesAccepted(String limit) {
      var environment = new MockEnvironment().withProperty(INVENTORY_LIMIT, limit);

      assertThatCode(() -> HttpClientProperties.resolve(environment)).doesNotThrowAnyException();
    }
  }
}
