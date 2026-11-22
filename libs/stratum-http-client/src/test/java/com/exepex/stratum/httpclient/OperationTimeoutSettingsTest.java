package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.ReportTimeoutSettings;
import com.exepex.stratum.httpclient.testsupport.SettingsChecks;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("An operation's timeouts win over its client's, which win over the defaults")
class OperationTimeoutSettingsTest {

  /** The start of the settings of the inventory client's list-orders operation. */
  private static final String LIST_ORDERS =
      HttpClientTestData.INVENTORY_SETTINGS + ".operations." + HttpClientTestData.LIST_ORDERS;

  /** The end of a read-timeout key, before its value. */
  private static final String READ_TIMEOUT = ".read-timeout=";

  /** The end of a total-timeout key, before its value. */
  private static final String TOTAL_TIMEOUT = ".total-timeout=";

  /** The operation's own read-timeout in most tests. */
  private static final Duration OWN_READ_TIMEOUT = Duration.ofSeconds(20);

  /** The operation's own total-timeout in most tests. */
  private static final Duration OWN_TOTAL_TIMEOUT = Duration.ofSeconds(30);

  /** The client's total-timeout, which an operation that sets none takes. */
  private static final Duration CLIENT_TOTAL_TIMEOUT = Duration.ofSeconds(6);

  /** The operation's read-timeout in the .yml file. */
  private static final Duration YAML_READ_TIMEOUT = Duration.ofSeconds(40);

  /** The operation's total-timeout in the .yml file. */
  private static final Duration YAML_TOTAL_TIMEOUT = Duration.ofSeconds(45);

  /** Returns a duration as a setting's value, such as {@code 20s}. */
  private String seconds(Duration duration) {
    return duration.toSeconds() + "s";
  }

  /** Returns the inventory client's settings from the settings given as {@code key=value}. */
  private ClientSettings inventoryOf(String... settings) {
    return HttpClientProperties.resolve(SettingsChecks.environmentOf(settings))
        .clientSettings(HttpClientTestData.INVENTORY);
  }

  /** Returns the list-orders operation's settings, merged over its client's. */
  private OperationSettings listOrdersOf(ClientSettings client) {
    return client.operations().get(HttpClientTestData.LIST_ORDERS).orElse(client);
  }

  @Nested
  @DisplayName("when an operation's settings merge with its client's")
  class Merge {

    @Test
    @DisplayName("an operation's own timeouts win")
    void operationWins() {
      var client =
          inventoryOf(
              HttpClientTestData.INVENTORY_SETTINGS + READ_TIMEOUT + "4s",
              HttpClientTestData.INVENTORY_SETTINGS + TOTAL_TIMEOUT + seconds(CLIENT_TOTAL_TIMEOUT),
              LIST_ORDERS + READ_TIMEOUT + seconds(OWN_READ_TIMEOUT),
              LIST_ORDERS + TOTAL_TIMEOUT + seconds(OWN_TOTAL_TIMEOUT));

      assertThat(listOrdersOf(client))
          .returns(OWN_READ_TIMEOUT, OperationSettings::readTimeout)
          .returns(OWN_TOTAL_TIMEOUT, OperationSettings::totalTimeout);
    }

    @Test
    @DisplayName("a timeout the operation leaves unset is the client's")
    void unsetTakesClients() {
      var client =
          inventoryOf(
              HttpClientTestData.INVENTORY_SETTINGS + TOTAL_TIMEOUT + seconds(CLIENT_TOTAL_TIMEOUT),
              LIST_ORDERS + READ_TIMEOUT + seconds(OWN_READ_TIMEOUT));

      assertThat(listOrdersOf(client))
          .returns(OWN_READ_TIMEOUT, OperationSettings::readTimeout)
          .returns(CLIENT_TOTAL_TIMEOUT, OperationSettings::totalTimeout);
    }

    @Test
    @DisplayName(
        "a timeout the operation and its client leave unset comes from defaults, then Stratum")
    void unsetTakesDefaults() {
      var client =
          inventoryOf(
              HttpClientConstants.DEFAULTS
                  + READ_TIMEOUT
                  + seconds(HttpClientTestData.SHARED_READ_TIMEOUT),
              LIST_ORDERS + ".errors[404]=O-1");

      assertThat(listOrdersOf(client))
          .returns(HttpClientTestData.SHARED_READ_TIMEOUT, OperationSettings::readTimeout)
          .returns(HttpClientConstants.DEFAULT_TOTAL_TIMEOUT, OperationSettings::totalTimeout);
    }

    @Test
    @DisplayName("keys an operation does not read leave the client's connect timeout and retries")
    void otherKeysStayPerClient() {
      var client =
          inventoryOf(
              HttpClientTestData.INVENTORY_SETTINGS + ".connect-timeout=1s",
              HttpClientTestData.INVENTORY_RETRY + ".max-retries=2",
              LIST_ORDERS + ".connect-timeout=9s",
              LIST_ORDERS + ".retry.max-retries=7",
              LIST_ORDERS + READ_TIMEOUT + seconds(OWN_READ_TIMEOUT));

      assertThat(client.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
      assertThat(client.retry().maxRetries()).isEqualTo(2);
      assertThat(client.operations().get(HttpClientTestData.LIST_ORDERS))
          .isEqualTo(OperationSettings.builder().readTimeout(OWN_READ_TIMEOUT).build());
    }
  }

  @Nested
  @DisplayName("the startup check")
  class Checks {

    /** Operation timeouts of 0 or less, each of which stops startup. */
    private static final List<String> NOT_POSITIVE =
        List.of(
            LIST_ORDERS + READ_TIMEOUT + "0s",
            LIST_ORDERS + READ_TIMEOUT + "-1s",
            LIST_ORDERS + TOTAL_TIMEOUT + "0s",
            LIST_ORDERS + TOTAL_TIMEOUT + "-1s");

    @ParameterizedTest
    @FieldSource("NOT_POSITIVE")
    @DisplayName("stops startup on an operation timeout of 0 or less, naming durations-positive")
    void notPositiveStopsStartup(String setting) {
      SettingsChecks.assertSettingsInvalid("durations-positive", setting);
    }

    @Test
    @DisplayName("accepts a read-timeout longer than the total-timeout, as for a client")
    void readAboveTotalAccepted() {
      var client =
          inventoryOf(
              LIST_ORDERS + READ_TIMEOUT + seconds(OWN_READ_TIMEOUT),
              LIST_ORDERS + TOTAL_TIMEOUT + "5s");

      assertThat(listOrdersOf(client).readTimeout()).isEqualTo(OWN_READ_TIMEOUT);
    }
  }

  @Nested
  @DisplayName("from properties, YAML and Java")
  class Binding {

    @Test
    @DisplayName("both timeouts are read from a .yml file")
    void fromYaml() throws IOException {
      var client =
          HttpClientProperties.resolve(HttpClientTestData.yamlEnvironment())
              .clientSettings(HttpClientTestData.INVENTORY);

      assertThat(listOrdersOf(client))
          .returns(YAML_READ_TIMEOUT, OperationSettings::readTimeout)
          .returns(YAML_TOTAL_TIMEOUT, OperationSettings::totalTimeout);
    }

    @Test
    @DisplayName(HttpClientTestData.JAVA_CONFIGURATION_WINS)
    void javaWinsOverFiles(@TempDir Path folder) throws IOException {
      var environment =
          SettingsChecks.environmentOf(
              LIST_ORDERS + READ_TIMEOUT + seconds(OWN_READ_TIMEOUT),
              LIST_ORDERS + TOTAL_TIMEOUT + seconds(OWN_TOTAL_TIMEOUT));

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              HttpClientPropertiesCustomizer.class,
              List.of(ReportTimeoutSettings.class),
              () -> HttpClientProperties.resolve(environment));

      assertThat(listOrdersOf(properties.clientSettings(HttpClientTestData.INVENTORY)))
          .returns(ReportTimeoutSettings.READ_TIMEOUT, OperationSettings::readTimeout)
          .returns(ReportTimeoutSettings.TOTAL_TIMEOUT, OperationSettings::totalTimeout);
    }
  }
}
