package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.SlowInventorySettings;
import com.exepex.stratum.httpclient.testsupport.VendorKeySettings;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
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
      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).interfaces())
          .isEqualTo(HttpClientTestData.PARTNER_INTERFACES);
    }

    @Test
    @DisplayName("a client's generated interfaces are read as a list from properties")
    void interfacesFromProperties() {
      var environment =
          new MockEnvironment()
              .withProperty(
                  HttpClientTestData.INVENTORY_SETTINGS + ".interfaces[0]",
                  HttpClientTestData.PARTNER_INTERFACES.get(0))
              .withProperty(
                  HttpClientTestData.INVENTORY_SETTINGS + ".interfaces[1]",
                  HttpClientTestData.PARTNER_INTERFACES.get(1));

      assertThat(
              HttpClientProperties.resolve(environment)
                  .clientSettings(HttpClientTestData.INVENTORY)
                  .interfaces())
          .isEqualTo(HttpClientTestData.PARTNER_INTERFACES);
    }

    @Test
    @DisplayName("auth and an SSL bundle are read from properties, with the API key's own header")
    void authFromProperties() {
      var environment =
          new MockEnvironment()
              .withProperty(
                  HttpClientTestData.INVENTORY_AUTH + ".api-key", HttpClientTestData.SECRET)
              .withProperty(HttpClientTestData.INVENTORY_AUTH + ".api-key-header", "X-Vendor-Key")
              .withProperty(HttpClientTestData.INVENTORY_SETTINGS + ".ssl-bundle", "bank");

      var settings =
          HttpClientProperties.resolve(environment).clientSettings(HttpClientTestData.INVENTORY);

      assertThat(settings.auth())
          .isEqualTo(
              ClientAuth.builder()
                  .apiKey(HttpClientTestData.SECRET)
                  .apiKeyHeader("X-Vendor-Key")
                  .build());
      assertThat(settings.sslBundle()).isEqualTo("bank");
    }

    @Test
    @DisplayName("Basic credentials are read from a .yml file")
    void basicFromYaml() throws IOException {
      var properties = HttpClientProperties.resolve(HttpClientTestData.yamlEnvironment());

      assertThat(properties.clientSettings(HttpClientTestData.PARTNER).auth())
          .isEqualTo(
              ClientAuth.builder()
                  .basic(new BasicCredentials("orders", HttpClientTestData.SECRET))
                  .build());
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

    @Test
    @DisplayName("a customizer sets a client's auth and SSL bundle, and wins over the files")
    void javaSetsAuth(@TempDir Path folder) throws IOException {
      var environment =
          new MockEnvironment()
              .withProperty(
                  HttpClientTestData.INVENTORY_AUTH + ".bearer", HttpClientTestData.SECRET);

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              HttpClientPropertiesCustomizer.class,
              List.of(VendorKeySettings.class),
              () -> HttpClientProperties.resolve(environment));

      var settings = properties.clientSettings(HttpClientTestData.INVENTORY);
      assertThat(settings.auth()).isEqualTo(VendorKeySettings.AUTH);
      assertThat(settings.sslBundle()).isEqualTo(VendorKeySettings.SSL_BUNDLE);
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

    @Test
    @DisplayName("generated interfaces under defaults")
    void interfacesInDefaults() {
      assertInvalid(
          HttpClientConstants.DEFAULTS + ".interfaces[0]",
          HttpClientTestData.PARTNER_INTERFACES.get(0),
          "defaults-without-interfaces");
    }

    @Test
    @DisplayName("an SSL bundle under defaults")
    void sslBundleInDefaults() {
      assertInvalid(
          HttpClientConstants.DEFAULTS + ".ssl-bundle", "bank", "defaults-without-ssl-bundle");
    }

    @Test
    @DisplayName("an SSL bundle on a client that uses the shared pool")
    void sslBundleOnSharedPool() {
      assertSettingsInvalid(
          "shared-pool-without-ssl-bundle",
          HttpClientTestData.INVENTORY_SETTINGS + ".shared-pool=true",
          HttpClientTestData.INVENTORY_SETTINGS + ".ssl-bundle=bank");
    }

    @Test
    @DisplayName("an SSL bundle on a client that takes the shared pool from defaults")
    void sslBundleOnSharedPoolFromDefaults() {
      assertSettingsInvalid(
          "shared-pool-without-ssl-bundle",
          HttpClientConstants.DEFAULTS + ".shared-pool=true",
          HttpClientTestData.INVENTORY_SETTINGS + ".ssl-bundle=bank");
    }

    @Test
    @DisplayName("auth under defaults, without showing the secret")
    void authInDefaults() {
      assertInvalid(
          HttpClientConstants.DEFAULTS + ".auth.bearer",
          HttpClientTestData.SECRET,
          "defaults-without-auth");
    }

    @Test
    @DisplayName("two kinds of auth on one client, without showing either secret")
    void twoKinds() {
      assertSettingsInvalid(
          "auth-one-kind",
          HttpClientTestData.INVENTORY_AUTH + ".bearer=" + HttpClientTestData.SECRET,
          HttpClientTestData.INVENTORY_AUTH + ".api-key=" + HttpClientTestData.SECRET);
    }

    @Test
    @DisplayName("Basic credentials without a password")
    void basicWithoutPassword() {
      assertSettingsInvalid(
          "basic-complete", HttpClientTestData.INVENTORY_AUTH + ".basic.username=orders");
    }

    @Test
    @DisplayName("a header for an API key with another kind of auth")
    void apiKeyHeaderWithoutKey() {
      assertSettingsInvalid(
          "api-key-header-with-api-key",
          HttpClientTestData.INVENTORY_AUTH + ".bearer=" + HttpClientTestData.SECRET,
          HttpClientTestData.INVENTORY_AUTH + ".api-key-header=X-Key");
    }

    /** Checks that one setting stops startup with a message that names only the broken rule. */
    private void assertInvalid(String key, String value, String rule) {
      assertSettingsInvalid(rule, key + "=" + value);
    }

    /**
     * Checks that settings, each {@code key=value}, stop startup with a message that names only the
     * broken rule, never a value.
     */
    private void assertSettingsInvalid(String rule, String... settings) {
      var environment = new MockEnvironment();
      Arrays.stream(settings)
          .map(setting -> setting.split("=", 2))
          .forEach(keyAndValue -> environment.setProperty(keyAndValue[0], keyAndValue[1]));

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
