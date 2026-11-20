package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.OrderErrorCodeSettings;
import com.exepex.stratum.httpclient.testsupport.OutOfRangeErrorCodeSettings;
import com.exepex.stratum.httpclient.testsupport.SettingsChecks;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("A client's error codes merge by status: operation over client over defaults")
class ErrorCodeSettingsTest {

  /** The shared error codes. */
  private static final String DEFAULT_ERRORS = HttpClientConstants.DEFAULTS + ".errors";

  /** The inventory client's error codes. */
  private static final String INVENTORY_ERRORS = HttpClientTestData.INVENTORY_SETTINGS + ".errors";

  /** The error codes of the inventory client's list-orders operation. */
  private static final String LIST_ORDERS_ERRORS =
      HttpClientTestData.INVENTORY_SETTINGS
          + ".operations."
          + HttpClientTestData.LIST_ORDERS
          + ".errors";

  /** The service's code for a conflict in most tests. */
  private static final String CONFLICT_CODE = "ORD-0409";

  /** The service's code for a missing order in most tests. */
  private static final String NOT_FOUND_CODE = "ORD-0404";

  /** Returns one mapping as the end of a setting, such as {@code [409]=ORD-0409}. */
  private String mapped(int status, String code) {
    return "[" + status + "]=" + code;
  }

  /** Returns the inventory client's settings from the settings given as {@code key=value}. */
  private ClientSettings inventoryOf(String... settings) {
    return HttpClientProperties.resolve(SettingsChecks.environmentOf(settings))
        .clientSettings(HttpClientTestData.INVENTORY);
  }

  @Nested
  @DisplayName("when a client's settings merge with the defaults")
  class Merge {

    @Test
    @DisplayName("a status only under defaults applies to a client that maps another status")
    void defaultsOnlyStatusApplies() {
      var settings =
          inventoryOf(
              DEFAULT_ERRORS + mapped(HttpClientTestData.UNAUTHORIZED, "ORD-0401"),
              INVENTORY_ERRORS + mapped(HttpClientTestData.CONFLICT, CONFLICT_CODE));

      assertThat(settings.errorsOf(null))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  HttpClientTestData.UNAUTHORIZED,
                  "ORD-0401",
                  HttpClientTestData.CONFLICT,
                  CONFLICT_CODE));
    }

    @Test
    @DisplayName("a status at client and defaults takes the client's code")
    void clientWinsOverDefaults() {
      var settings =
          inventoryOf(
              DEFAULT_ERRORS + mapped(HttpClientTestData.CONFLICT, "ORD-9409"),
              INVENTORY_ERRORS + mapped(HttpClientTestData.CONFLICT, CONFLICT_CODE));

      assertThat(settings.errorsOf(null))
          .containsExactly(Map.entry(HttpClientTestData.CONFLICT, CONFLICT_CODE));
    }

    @Test
    @DisplayName("a status at all three levels takes the operation's code, the client's elsewhere")
    void operationWinsOverClientAndDefaults() {
      var settings =
          inventoryOf(
              DEFAULT_ERRORS + mapped(HttpClientTestData.NOT_FOUND, "ORD-9404"),
              INVENTORY_ERRORS + mapped(HttpClientTestData.NOT_FOUND, NOT_FOUND_CODE),
              LIST_ORDERS_ERRORS + mapped(HttpClientTestData.NOT_FOUND, "ORD-4040"));

      assertThat(settings.errorsOf(HttpClientTestData.LIST_ORDERS))
          .containsExactly(Map.entry(HttpClientTestData.NOT_FOUND, "ORD-4040"));
      assertThat(settings.errorsOf(null))
          .containsExactly(Map.entry(HttpClientTestData.NOT_FOUND, NOT_FOUND_CODE));
      assertThat(settings.errorsOf("unknown"))
          .containsExactly(Map.entry(HttpClientTestData.NOT_FOUND, NOT_FOUND_CODE));
    }

    @Test
    @DisplayName("with nothing set, a client maps no status and has no operation")
    void noneByDefault() {
      var settings = inventoryOf();

      assertThat(settings.errorsOf(null)).isEmpty();
      assertThat(settings.operations()).isEmpty();
    }

    @Test
    @DisplayName("an operation belongs to its client: the defaults' operations are not merged in")
    void defaultsOperationsNotMerged() {
      var properties =
          HttpClientProperties.resolve(
              SettingsChecks.environmentOf(
                  HttpClientConstants.DEFAULTS + ".operations.list-orders.errors[404]=ORD-0404",
                  INVENTORY_ERRORS + mapped(HttpClientTestData.CONFLICT, CONFLICT_CODE)));

      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).operations()).isEmpty();
      assertThat(properties.defaults().operations()).containsKey(HttpClientTestData.LIST_ORDERS);
    }
  }

  @Nested
  @DisplayName("an entry that breaks a rule stops startup with SX-CORE-1003")
  class Refused {

    /** Settings that each break one rule of a status, a code or an operation's name. */
    private static final List<String> BROKEN =
        List.of(
            INVENTORY_ERRORS + "[399]=ORD-0399",
            INVENTORY_ERRORS + "[600]=ORD-0600",
            DEFAULT_ERRORS + "[999]=ORD-0999",
            INVENTORY_ERRORS + "[409]=",
            INVENTORY_ERRORS + "[409]=ORD 0409",
            INVENTORY_ERRORS + "[409]=SX-HTTPC-0007",
            LIST_ORDERS_ERRORS + "[404]=SX-WEB-1005",
            LIST_ORDERS_ERRORS + "[700]=ORD-0700",
            HttpClientTestData.INVENTORY_SETTINGS + ".operations.List_Orders.errors[404]=ORD-1");

    @ParameterizedTest
    @FieldSource("BROKEN")
    @DisplayName("naming the key and the rule, never the code or operation")
    void brokenEntryStopsStartup(String setting) {
      var environment = SettingsChecks.environmentOf(setting);

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> HttpClientProperties.resolve(environment))
          .withMessageStartingWith(HttpClientTestData.INVALID_SETTINGS)
          .withMessageContaining(HttpClientConstants.PREFIX)
          .withMessageNotContaining("ORD")
          .withMessageNotContaining("SX-HTTPC-0007")
          .withMessageNotContaining("SX-WEB-1005")
          .withMessageNotContaining("List_Orders");
    }

    @Test
    @DisplayName("a status that is not a number fails as the key is read")
    void textStatusStopsStartup() {
      var environment = SettingsChecks.environmentOf(INVENTORY_ERRORS + ".abc=ORD-0001");

      assertThatRuntimeException().isThrownBy(() -> HttpClientProperties.resolve(environment));
    }

    @Test
    @DisplayName("a status set in Java outside 400 to 599 is refused after the customizer ran")
    void javaValueOutOfRangeRefused(@TempDir Path folder) {
      var environment = SettingsChecks.environmentOf();
      var customizers =
          List.<Class<? extends HttpClientPropertiesCustomizer>>of(
              OutOfRangeErrorCodeSettings.class);

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(
              () ->
                  StarterTestSupport.withCustomizers(
                      folder,
                      HttpClientPropertiesCustomizer.class,
                      customizers,
                      () -> HttpClientProperties.resolve(environment)))
          .withMessageStartingWith(HttpClientTestData.INVALID_SETTINGS)
          .withMessageNotContaining("700");
    }
  }

  @Nested
  @DisplayName("from properties, YAML and Java")
  class Binding {

    @Test
    @DisplayName("a status in brackets and a plain one are both read")
    void fromProperties() {
      var settings =
          inventoryOf(
              INVENTORY_ERRORS + mapped(HttpClientTestData.CONFLICT, CONFLICT_CODE),
              INVENTORY_ERRORS + ".422=ORD-0422",
              LIST_ORDERS_ERRORS + mapped(HttpClientTestData.NOT_FOUND, NOT_FOUND_CODE));

      assertThat(settings.errorsOf(HttpClientTestData.LIST_ORDERS))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  HttpClientTestData.NOT_FOUND,
                  NOT_FOUND_CODE,
                  HttpClientTestData.CONFLICT,
                  CONFLICT_CODE,
                  HttpClientTestData.UNPROCESSABLE,
                  "ORD-0422"));
    }

    @Test
    @DisplayName("the codes are read from a .yml file, quoted and plain, at all three levels")
    void fromYaml() throws IOException {
      var settings =
          HttpClientProperties.resolve(HttpClientTestData.yamlEnvironment())
              .clientSettings(HttpClientTestData.INVENTORY);

      assertThat(settings.errorsOf(HttpClientTestData.LIST_ORDERS))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  HttpClientTestData.UNAUTHORIZED,
                  "ORD-0401",
                  HttpClientTestData.NOT_FOUND,
                  NOT_FOUND_CODE,
                  HttpClientTestData.CONFLICT,
                  CONFLICT_CODE,
                  HttpClientTestData.UNPROCESSABLE,
                  "ORD-0422"));
      assertThat(settings.errorsOf(null)).doesNotContainKey(HttpClientTestData.NOT_FOUND);
    }

    @Test
    @DisplayName(HttpClientTestData.JAVA_CONFIGURATION_WINS)
    void javaWinsOverFiles(@TempDir Path folder) throws IOException {
      var environment =
          SettingsChecks.environmentOf(
              INVENTORY_ERRORS + mapped(HttpClientTestData.CONFLICT, CONFLICT_CODE),
              LIST_ORDERS_ERRORS + mapped(HttpClientTestData.NOT_FOUND, NOT_FOUND_CODE));

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              HttpClientPropertiesCustomizer.class,
              List.of(OrderErrorCodeSettings.class),
              () -> HttpClientProperties.resolve(environment));

      var settings = properties.clientSettings(HttpClientTestData.INVENTORY);
      assertThat(settings.errors()).isEqualTo(OrderErrorCodeSettings.ERRORS);
      assertThat(settings.operations()).isEqualTo(OrderErrorCodeSettings.OPERATIONS);
    }
  }
}
