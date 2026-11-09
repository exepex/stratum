package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.SlowInventorySettings;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("HttpClientProperties come from properties, changed by Java configuration, which wins")
class HttpClientPropertiesTest {

  @Nested
  @DisplayName("with nothing set")
  class Defaults {

    @Test
    @DisplayName("with nothing set, a client gets Stratum's defaults and no address")
    void defaultsToStratumValues() {
      var settings =
          HttpClientProperties.resolve(new MockEnvironment())
              .clientSettings(HttpClientTestData.INVENTORY);

      assertThat(settings)
          .isEqualTo(
              ClientSettings.builder()
                  .connectTimeout(HttpClientConstants.DEFAULT_CONNECT_TIMEOUT)
                  .timeout(HttpClientConstants.DEFAULT_TIMEOUT)
                  .maxConnections(HttpClientConstants.DEFAULT_MAX_CONNECTIONS)
                  .maxWait(HttpClientConstants.DEFAULT_MAX_WAIT)
                  .idleTimeout(HttpClientConstants.DEFAULT_IDLE_TIMEOUT)
                  .connectionLifetime(HttpClientConstants.DEFAULT_CONNECTION_LIFETIME)
                  .sharedPool(false)
                  .build());
    }
  }

  @Nested
  @DisplayName("from properties and YAML")
  class Binding {

    @Test
    @DisplayName("a client's own setting wins over the shared default, which fills the rest")
    void clientValueWins() {
      var environment =
          new MockEnvironment()
              .withProperty(HttpClientConstants.DEFAULTS + ".timeout", "3s")
              .withProperty(
                  HttpClientConstants.DEFAULTS + ".max-connections",
                  String.valueOf(HttpClientTestData.SHARED_MAX_CONNECTIONS))
              .withProperty(
                  HttpClientTestData.INVENTORY_SETTINGS + ".url", HttpClientTestData.INVENTORY_URL)
              .withProperty(HttpClientTestData.INVENTORY_SETTINGS + ".timeout", "20s");

      var settings =
          HttpClientProperties.resolve(environment).clientSettings(HttpClientTestData.INVENTORY);

      assertThat(settings.url()).isEqualTo(HttpClientTestData.INVENTORY_URL);
      assertThat(settings.timeout()).isEqualTo(HttpClientTestData.REPORT_TIMEOUT);
      assertThat(settings.maxConnections()).isEqualTo(HttpClientTestData.SHARED_MAX_CONNECTIONS);
      assertThat(settings.connectTimeout()).isEqualTo(HttpClientConstants.DEFAULT_CONNECT_TIMEOUT);
    }

    @Test
    @DisplayName("settings are read from a .yml file")
    void readFromYaml() throws IOException {
      var properties = HttpClientProperties.resolve(HttpClientTestData.yamlEnvironment());

      assertThat(properties.defaults().timeout()).isEqualTo(HttpClientTestData.SHARED_TIMEOUT);
      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).timeout())
          .isEqualTo(HttpClientTestData.INVENTORY_TIMEOUT);
      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).url())
          .isEqualTo(HttpClientTestData.INVENTORY_URL);
    }
  }

  @Nested
  @DisplayName("from Java configuration")
  class JavaConfiguration {

    @Test
    @DisplayName(HttpClientTestData.JAVA_CONFIGURATION_WINS)
    void javaWinsOverFiles(@TempDir Path folder) throws IOException {
      var environment =
          new MockEnvironment()
              .withProperty(
                  HttpClientTestData.INVENTORY_SETTINGS + ".url", HttpClientTestData.INVENTORY_URL)
              .withProperty(HttpClientTestData.INVENTORY_SETTINGS + ".timeout", "1s");

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              HttpClientPropertiesCustomizer.class,
              List.of(SlowInventorySettings.class),
              () -> HttpClientProperties.resolve(environment));

      var settings = properties.clientSettings(HttpClientTestData.INVENTORY);
      assertThat(settings.timeout()).isEqualTo(HttpClientTestData.REPORT_TIMEOUT);
      assertThat(settings.url()).isEqualTo(HttpClientTestData.INVENTORY_URL);
    }
  }

  @Nested
  @DisplayName("a setting that breaks its rules stops startup with SX-CORE-1003")
  class InvalidSettings {

    @Test
    @DisplayName("a duration of 0")
    void zeroDuration() {
      assertInvalid(
          HttpClientTestData.INVENTORY_SETTINGS + ".max-wait", "0s", "durations-positive");
    }

    @Test
    @DisplayName("a pool above 10000 connections")
    void poolTooLarge() {
      assertInvalid(
          HttpClientConstants.DEFAULTS + ".max-connections", "10001", "max-connections-in-range");
    }

    @Test
    @DisplayName("a pool of 0 connections")
    void poolEmpty() {
      assertInvalid(
          HttpClientTestData.INVENTORY_SETTINGS + ".max-connections",
          "0",
          "max-connections-in-range");
    }

    @Test
    @DisplayName("a client name that is not a valid key part, without showing the name")
    void badClientName() {
      assertInvalid(
          HttpClientConstants.CLIENTS + ".Inventory_V2.url", "http://x", "client-names-valid");
    }

    @Test
    @DisplayName("an address under defaults")
    void urlInDefaults() {
      assertInvalid(HttpClientConstants.DEFAULTS + ".url", "http://x", "defaults-without-url");
    }

    /** Checks that one setting stops startup with a message that names only the broken rule. */
    private void assertInvalid(String key, String value, String rule) {
      var environment = new MockEnvironment().withProperty(key, value);

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> HttpClientProperties.resolve(environment))
          .withMessage(
              HttpClientTestData.INVALID_SETTINGS
                  + HttpClientConstants.PREFIX
                  + "."
                  + rule
                  + " breaks @AssertTrue");
    }
  }
}
