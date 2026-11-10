package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.LargeAnswerSettings;
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

@DisplayName("response-body-limit comes from the files and Java, and is refused out of range")
class ResponseBodyLimitSettingsTest {

  /** The inventory client's own limit, as in {@code http-client.yml}. */
  private static final DataSize CLIENT_LIMIT = DataSize.ofMegabytes(64);

  /** The shared limit's key. */
  private static final String DEFAULT_LIMIT = HttpClientConstants.DEFAULTS + ".response-body-limit";

  /** The inventory client's limit key. */
  private static final String INVENTORY_LIMIT =
      HttpClientTestData.INVENTORY_SETTINGS + ".response-body-limit";

  @Nested
  @DisplayName("from properties, YAML and Java")
  class Binding {

    @Test
    @DisplayName(
        "a client's own limit wins, and a client that sets none keeps 32MB, from properties")
    void clientWinsFromProperties() {
      var environment = new MockEnvironment().withProperty(INVENTORY_LIMIT, "64MB");

      var properties = HttpClientProperties.resolve(environment);

      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).responseBodyLimit())
          .isEqualTo(CLIENT_LIMIT);
      assertThat(properties.clientSettings(HttpClientTestData.PARTNER).responseBodyLimit())
          .isEqualTo(HttpClientConstants.DEFAULT_RESPONSE_BODY_LIMIT);
    }

    @Test
    @DisplayName("a client's own limit wins, and a client that sets none keeps 32MB, from a .yml")
    void clientWinsFromYaml() throws IOException {
      var properties = HttpClientProperties.resolve(HttpClientTestData.yamlEnvironment());

      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).responseBodyLimit())
          .isEqualTo(CLIENT_LIMIT);
      assertThat(properties.clientSettings(HttpClientTestData.PARTNER).responseBodyLimit())
          .isEqualTo(HttpClientConstants.DEFAULT_RESPONSE_BODY_LIMIT);
    }

    @Test
    @DisplayName("-1 under defaults turns the limit off for every client")
    void noLimitShared() {
      var environment = new MockEnvironment().withProperty(DEFAULT_LIMIT, "-1");

      var properties = HttpClientProperties.resolve(environment);

      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).responseBodyLimit())
          .isEqualTo(HttpClientConstants.NO_RESPONSE_BODY_LIMIT);
    }

    @Test
    @DisplayName(HttpClientTestData.JAVA_CONFIGURATION_WINS)
    void javaWinsOverFiles(@TempDir Path folder) throws IOException {
      var environment = new MockEnvironment().withProperty(INVENTORY_LIMIT, "64MB");

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              HttpClientPropertiesCustomizer.class,
              List.of(LargeAnswerSettings.class),
              () -> HttpClientProperties.resolve(environment));

      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).responseBodyLimit())
          .isEqualTo(LargeAnswerSettings.LIMIT);
    }
  }

  @Nested
  @DisplayName("out of range: -1, or from 1 byte to 1 gigabyte")
  class Range {

    /** Limits that stop startup: 0, a negative one other than -1, and over 1 GB. */
    private static final List<String> REFUSED = List.of("0", "-2B", "1073741825B", "2GB");

    /** No limit, and the smallest and the largest limit allowed. */
    private static final List<String> ACCEPTED = List.of("-1", "-1B", "1B", "1GB");

    @ParameterizedTest(name = "{0}")
    @FieldSource("REFUSED")
    @DisplayName("a client's limit stops startup, naming the key, never the value or the client")
    void clientLimitRefused(String limit) {
      SettingsChecks.assertSettingsInvalid(
          "clients[*].response-body-limit-in-range", INVENTORY_LIMIT + "=" + limit);
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("REFUSED")
    @DisplayName("a shared limit stops startup, naming the key, never the value")
    void sharedLimitRefused(String limit) {
      SettingsChecks.assertSettingsInvalid(
          "defaults.response-body-limit-in-range", DEFAULT_LIMIT + "=" + limit);
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("ACCEPTED")
    @DisplayName("no limit, and a limit at either end of the range, are accepted")
    void edgesAccepted(String limit) {
      var environment = new MockEnvironment().withProperty(INVENTORY_LIMIT, limit);

      assertThatCode(() -> HttpClientProperties.resolve(environment)).doesNotThrowAnyException();
    }
  }
}
