package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.SettingsChecks;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

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
                  .retry(HttpClientTestData.STRATUM_RETRY)
                  .totalTimeout(HttpClientConstants.DEFAULT_TOTAL_TIMEOUT)
                  .circuitBreaker(HttpClientTestData.STRATUM_CIRCUIT_BREAKER)
                  .mock(false)
                  .interceptorBodyLimit(DataSize.ofMegabytes(1))
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
              .withProperty(HttpClientTestData.INVENTORY_SETTINGS + ".timeout", "20s")
              .withProperty(HttpClientTestData.DEFAULTS_RETRY + ".max-retries", "5")
              .withProperty(HttpClientTestData.INVENTORY_RETRY + ".max-retries", "0")
              .withProperty(HttpClientTestData.INVENTORY_BREAKER + ".enabled", "false");

      var settings =
          HttpClientProperties.resolve(environment).clientSettings(HttpClientTestData.INVENTORY);

      assertThat(settings.url()).isEqualTo(HttpClientTestData.INVENTORY_URL);
      assertThat(settings.timeout()).isEqualTo(HttpClientTestData.REPORT_TIMEOUT);
      assertThat(settings.maxConnections()).isEqualTo(HttpClientTestData.SHARED_MAX_CONNECTIONS);
      assertThat(settings.connectTimeout()).isEqualTo(HttpClientConstants.DEFAULT_CONNECT_TIMEOUT);
      assertThat(settings.retry().maxRetries()).isZero();
      assertThat(settings.circuitBreaker().enabled()).isFalse();
      assertThat(settings.totalTimeout()).isEqualTo(HttpClientConstants.DEFAULT_TOTAL_TIMEOUT);
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
      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).totalTimeout())
          .isEqualTo(HttpClientTestData.REPORT_TOTAL_TIMEOUT);
      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).mock()).isTrue();
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

    @Test
    @DisplayName("a service token is read from a .yml file, and token relay from properties")
    void tokenAuthFromBothFormats() throws IOException {
      var fromYaml = HttpClientProperties.resolve(HttpClientTestData.yamlEnvironment());
      var fromProperties =
          HttpClientProperties.resolve(
              new MockEnvironment()
                  .withProperty(HttpClientTestData.INVENTORY_AUTH + ".token-relay", "true"));

      assertThat(fromYaml.clientSettings("billing").auth())
          .isEqualTo(ClientAuth.builder().clientCredentials("invoices").build());
      assertThat(fromProperties.clientSettings(HttpClientTestData.INVENTORY).auth())
          .isEqualTo(ClientAuth.builder().tokenRelay(true).build());
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
    @DisplayName("a total timeout of 0")
    void zeroTotalTimeout() {
      assertInvalid(
          HttpClientTestData.INVENTORY_SETTINGS + ".total-timeout", "0s", "durations-positive");
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
      SettingsChecks.assertSettingsInvalid(
          "shared-pool-without-ssl-bundle",
          HttpClientTestData.INVENTORY_SETTINGS + ".shared-pool=true",
          HttpClientTestData.INVENTORY_SETTINGS + ".ssl-bundle=bank");
    }

    @Test
    @DisplayName("an SSL bundle on a client that takes the shared pool from defaults")
    void sslBundleOnSharedPoolFromDefaults() {
      SettingsChecks.assertSettingsInvalid(
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
      SettingsChecks.assertSettingsInvalid(
          "auth-one-kind",
          HttpClientTestData.INVENTORY_AUTH + ".bearer=" + HttpClientTestData.SECRET,
          HttpClientTestData.INVENTORY_AUTH + ".api-key=" + HttpClientTestData.SECRET);
    }

    @Test
    @DisplayName("Basic credentials without a password")
    void basicWithoutPassword() {
      SettingsChecks.assertSettingsInvalid(
          "basic-complete", HttpClientTestData.INVENTORY_AUTH + ".basic.username=orders");
    }

    @Test
    @DisplayName("a header for an API key with another kind of auth")
    void apiKeyHeaderWithoutKey() {
      SettingsChecks.assertSettingsInvalid(
          "api-key-header-with-api-key",
          HttpClientTestData.INVENTORY_AUTH + ".bearer=" + HttpClientTestData.SECRET,
          HttpClientTestData.INVENTORY_AUTH + ".api-key-header=X-Key");
    }
  }

  /** Checks that one setting stops startup with a message that names only the broken rule. */
  private void assertInvalid(String key, String value, String rule) {
    SettingsChecks.assertSettingsInvalid(rule, key + "=" + value);
  }
}
